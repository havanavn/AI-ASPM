#!/usr/bin/env python3
"""
Load an inventory spreadsheet into the platform.

    python3 08_import_inventory_xlsx.py inventory.xlsx                 # validate only, writes nothing
    python3 08_import_inventory_xlsx.py inventory.xlsx --apply
    python3 08_import_inventory_xlsx.py inventory.xlsx --sheet "Hệ thống" --apply
    python3 08_import_inventory_xlsx.py inventory.xlsx --apply --emit-link-sql link.sql

IT VALIDATES BY DEFAULT AND WRITES ONLY WITH --apply
----------------------------------------------------
A first load of a real estate is the one run where a wrong mapping is cheapest to fix and most
expensive to discover later, so the default resolves every organization, tier, person and vocabulary
value against the live tenant, reports what it could not resolve, and writes nothing. `--apply` is a
second, deliberate command.

NO DEPENDENCY. An `.xlsx` is a zip of XML, so it is read with `zipfile` and `xml.etree` rather than
with `openpyxl`. A loader for a company's whole attack surface is a poor place to add a package
nobody has audited.

HOW THE COLUMNS MAP, AND WHERE THAT DECISION LIVES
--------------------------------------------------
`Product` becomes an APPLICATION and `Service` becomes a PROJECT beneath it, because the PROJECT
type is where this tenant's declared fields live — tech stack, access path, API count, WAF, abuse
controls, architecture link. Mapping a service to an APPLICATION instead would preserve the
hierarchy and lose seven of your columns, since the APPLICATION type declares five fields and they
are different ones.

⚠ **THE PARENT EDGE CANNOT BE CREATED THROUGH ANY API.** A project is attached to its application by
a `CONTAINS` edge in the composition graph, and no endpoint writes one: the project editor states
that it "deliberately cannot create one", the SBOM door attaches only the artifact it just created,
and there is no bulk-import endpoint. So this script creates both records and the project arrives
**with no application above it** — visible in the projects list with an empty application column,
invisible to every application-level rollup.

That is stated per row rather than hidden, and `--emit-link-sql` writes the statements that would
create the edges. It does **not** run them: they write `asset_relationship` directly, bypassing the
domain layer, so they are for somebody who has read them and accepts that.

WHAT IS NOT DROPPED SILENTLY
----------------------------
A column with no target field stops the run. `--unmapped note` appends it to the description instead,
`--unmapped ignore` discards it and says which. A tech stack or WAF value the tenant's vocabulary does
not contain is reported with the permitted list rather than guessed at — a wrong severity or a wrong
control is a fact somebody will plan work against.
"""

import argparse
import json
import re
import sys
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from collections import OrderedDict

from aspm_client import Aspm, ApiError

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/package/2006/relationships"}


# ==================================================================================================
# Reading the spreadsheet
# ==================================================================================================

def _column_index(reference: str) -> int:
    """`C7` -> 2. Cells carry their own reference, and a blank cell is simply absent from the row."""
    letters = re.match(r"([A-Z]+)", reference or "A").group(1)
    index = 0
    for character in letters:
        index = index * 26 + (ord(character) - ord("A") + 1)
    return index - 1


def read_xlsx(path: str, sheet_name: str | None) -> list[dict]:
    """
    Rows as dictionaries keyed by the header row, with the sheet's own header text preserved.

    Shared strings, inline strings and plain numbers are all handled; formulas are read as their
    cached value, which is what the file holds. A cell that is absent from the XML is an empty
    string here rather than a missing key, so a row with a blank column and a row without that
    column at all read the same way — the spreadsheet does not distinguish them either.
    """
    with zipfile.ZipFile(path) as book:
        shared = []
        if "xl/sharedStrings.xml" in book.namelist():
            for item in ET.fromstring(book.read("xl/sharedStrings.xml")).findall("m:si", NS):
                shared.append("".join(node.text or "" for node in item.iter(
                    "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t")))

        sheets = ET.fromstring(book.read("xl/workbook.xml")).find("m:sheets", NS)
        names = [s.get("name") for s in sheets]
        if sheet_name and sheet_name not in names:
            raise SystemExit(f"no sheet named {sheet_name!r}. Sheets: {', '.join(names)}")
        target = (names.index(sheet_name) if sheet_name else 0) + 1
        member = f"xl/worksheets/sheet{target}.xml"
        if member not in book.namelist():
            raise SystemExit(f"{member} is not in the workbook; sheets found: {', '.join(names)}")

        rows = []
        for row in ET.fromstring(book.read(member)).iter(
                "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row"):
            values = {}
            for cell in row.findall("m:c", NS):
                index = _column_index(cell.get("r"))
                kind = cell.get("t")
                if kind == "s":
                    node = cell.find("m:v", NS)
                    text = shared[int(node.text)] if node is not None and node.text else ""
                elif kind == "inlineStr":
                    node = cell.find("m:is", NS)
                    text = "".join(t.text or "" for t in node.iter(
                        "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t")
                    ) if node is not None else ""
                else:
                    node = cell.find("m:v", NS)
                    text = node.text if node is not None and node.text else ""
                values[index] = (text or "").strip()
            rows.append(values)

    if not rows:
        raise SystemExit("the sheet is empty")
    header_row = next((r for r in rows if any(v for v in r.values())), {})
    header = {index: text for index, text in header_row.items() if text}
    out = []
    for row in rows[rows.index(header_row) + 1:]:
        record = OrderedDict((text, row.get(index, "")) for index, text in header.items())
        if any(v for v in record.values()):
            out.append(record)
    return out


# ==================================================================================================
# The mapping — read this before running anything
# ==================================================================================================

def normalise(text: str) -> str:
    """
    Header matching that survives a typo, a slash, a bilingual label and stray spacing.

    **Accents are folded, not stripped**, and the difference is the whole point: dropping the
    non-ASCII bytes turns "Sản phẩm" into `snphm`, which matches nothing and silently leaves a
    Vietnamese column unmapped. Decomposing first turns it into `sanpham`, which is what a person
    would write. A header this fails on shows up as an unmapped column rather than as a wrong one.
    """
    decomposed = unicodedata.normalize("NFD", (text or "").lower())
    ascii_only = decomposed.encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]", "", ascii_only)


#: Spreadsheet header -> where the value goes. Aliases are matched after `normalise`, so
#: "Orgnization", "Organization" and "Tổ chức" all reach the same target.
MAPPING = [
    (["organization", "orgnization", "tochuc", "donvi"], "org_node", "the organization that owns it"),
    (["productsanpham", "product", "sanpham"], "application", "grouped under this application"),
    (["servicehethong", "service", "hethong"], "project", "the record this row becomes"),
    (["descriptionmieuta", "description", "mieuta", "mota"], "attr:description", None),
    (["systemownertechlead", "systemowner", "techlead", "owner"], "contact", None),
    (["productiondomain", "prod", "domainproduction"], "domain:PRODUCTION", None),
    (["uatdomain", "uat", "domainuat"], "domain:UAT", None),
    (["techstack", "congnghe"], "attr:tech_stack", None),
    (["accesspublicinternalztna", "access", "truycap"], "access", "exposure and access path"),
    (["linktdd", "tdd", "linkthietke"], "attr:architecture_url", None),
    (["linkrepo", "repo", "repository"], "repository", None),
    (["soluongapi", "apicount", "soapi"], "attr:api_count", None),
    (["businesscriticality", "criticality", "mucdoquantrong"], "criticality", None),
    (["mfa"], "auth:mfa", "folded into authentication_controls"),
    (["passwordpolicy", "chinhsachmatkhau"], "auth:password", "folded into authentication_controls"),
    (["waf"], "attr:waf", None),
    (["captcharatelimit", "captcha", "ratelimit"], "attr:abuse_controls", None),
]

#: Free text -> the tenant's vocabulary. Everything not listed is REPORTED, never guessed.
VALUES = {
    "access": {
        "public": ("INTERNET_PUBLIC", "DIRECT_INTERNET"),
        "internet": ("INTERNET_PUBLIC", "DIRECT_INTERNET"),
        "internal": ("INTERNAL_ONLY", "INTERNAL_NETWORK"),
        "noibo": ("INTERNAL_ONLY", "INTERNAL_NETWORK"),
        "ztna": ("INTERNAL_ONLY", "ZTNA"),
        "vpn": ("INTERNAL_ONLY", "VPN"),
        "partner": ("PARTNER_B2B", "PARTNER_LINK"),
    },
    "tech_stack": {
        "java": "JAVA", "springboot": "SPRING", "spring": "SPRING", "kotlin": "KOTLIN",
        "nodejs": "NODEJS", "node": "NODEJS", "nestjs": "NODEJS", "express": "EXPRESS",
        "python": "PYTHON", "django": "DJANGO", "go": "GO", "golang": "GO",
        "dotnet": "DOTNET", "net": "DOTNET", "csharp": "DOTNET", "php": "PHP",
        "laravel": "LARAVEL", "ruby": "RUBY", "rust": "RUST", "react": "REACT",
        "reactjs": "REACT", "angular": "ANGULAR", "vue": "VUE", "vuejs": "VUE",
        "nextjs": "NEXTJS", "flutter": "FLUTTER", "swift": "SWIFT", "android": "ANDROID_KOTLIN",
    },
    "waf": {
        "cloudflare": "CLOUDFLARE", "cf": "CLOUDFLARE", "awswaf": "AWS_WAF", "aws": "AWS_WAF",
        "azurewaf": "AZURE_WAF", "azure": "AZURE_WAF", "f5": "F5", "imperva": "IMPERVA",
        "modsecurity": "MODSECURITY", "modsec": "MODSECURITY",
        "none": "NONE", "no": "NONE", "khong": "NONE", "0": "NONE",
    },
    "abuse_controls": {
        "captcha": "CAPTCHA", "recaptcha": "CAPTCHA", "ratelimit": "RATE_LIMIT",
        "throttling": "RATE_LIMIT", "botmanagement": "BOT_MANAGEMENT", "bot": "BOT_MANAGEMENT",
        "none": "NONE", "no": "NONE", "khong": "NONE",
    },
    "mfa": {
        "totp": "MFA_TOTP", "otp": "MFA_TOTP", "googleauthenticator": "MFA_TOTP",
        "push": "MFA_PUSH", "hardware": "MFA_HARDWARE", "fido": "MFA_HARDWARE",
        "yes": "MFA_TOTP", "co": "MFA_TOTP", "y": "MFA_TOTP", "true": "MFA_TOTP",
        "sso": "SSO_OIDC", "oidc": "SSO_OIDC", "saml": "SSO_SAML",
    },
    "criticality": {
        "critical": "TIER1", "veryhigh": "TIER1", "high": "TIER1", "tier1": "TIER1",
        "1": "TIER1", "rathcao": "TIER1", "cao": "TIER1",
        "medium": "TIER2", "tier2": "TIER2", "2": "TIER2", "trungbinh": "TIER2",
        "low": "TIER3", "tier3": "TIER3", "3": "TIER3", "thap": "TIER3",
    },
}


def split_values(raw: str) -> list[str]:
    """A multi-value cell, however it was punctuated."""
    return [part.strip() for part in re.split(r"[,;/|\n]+", raw or "") if part.strip()]


def lookup(table: str, raw: str):
    return VALUES[table].get(normalise(raw))


# ==================================================================================================
# Planning a row
# ==================================================================================================

class Problem(Exception):
    """Something in the sheet the platform cannot accept. Reported per row, never guessed past."""


def plan_row(row: dict, columns: dict, tenant, unmapped_policy: str) -> dict:
    """Turn one spreadsheet row into the two records and one edge it describes."""
    def cell(target):
        header = columns.get(target)
        return (row.get(header) or "").strip() if header else ""

    project_name = cell("project")
    if not project_name:
        raise Problem("no service name in this row")

    org_name = cell("org_node")
    org = tenant["org_by_name"].get(normalise(org_name))
    if org_name and not org:
        raise Problem(f"no organization named {org_name!r}. "
                      f"Known: {', '.join(sorted(tenant['org_names'])[:6])}…")
    if not org:
        raise Problem("no organization in this row, and an asset must be owned by one")
    if not org.get("mayOwnAssets", True):
        raise Problem(f"{org_name!r} is a level that may not own assets; use a team beneath it")

    attributes, notes, warnings = {}, [], []

    description = cell("attr:description")
    if description:
        attributes["description"] = description

    # tech stack
    stack, unknown = [], []
    for value in split_values(cell("attr:tech_stack")):
        mapped = lookup("tech_stack", value)
        (stack.append(mapped) if mapped else unknown.append(value))
    if unknown:
        warnings.append(f"tech stack not in the tenant vocabulary, left unrecorded: "
                        f"{', '.join(unknown)}")
    if stack:
        attributes["tech_stack"] = sorted(set(stack))

    # access -> exposure (first class, risk-scored) AND access_path (declared)
    exposure = None
    access_raw = cell("access")
    if access_raw:
        mapped = lookup("access", access_raw)
        if not mapped:
            raise Problem(f"access {access_raw!r} is not one of "
                          f"{', '.join(sorted(VALUES['access']))}")
        exposure, attributes["access_path"] = mapped

    if cell("attr:architecture_url"):
        attributes["architecture_url"] = cell("attr:architecture_url")

    api_count = cell("attr:api_count")
    if api_count:
        digits = re.sub(r"[^0-9]", "", api_count)
        if not digits:
            warnings.append(f"API count {api_count!r} is not a number, left unrecorded")
        else:
            attributes["api_count"] = int(digits)

    # MFA and password policy both land in authentication_controls, which is what this tenant has
    controls, unknown_auth = [], []

    # MFA is a list of controls: "TOTP, Push" is two of them.
    for value in split_values(cell("auth:mfa")):
        mapped = lookup("mfa", value)
        if mapped:
            controls.append(mapped)
        elif normalise(value) in ("no", "khong", "none", "false", "0"):
            controls.append("PASSWORD_ONLY")
        else:
            unknown_auth.append(f"{columns.get('auth:mfa', 'MFA')}={value}")

    # Password policy is FREE TEXT and is NOT split. "Min 12 chars, rotate 90d" is one statement
    # about one policy; splitting it on the comma produced two notes reading "Min 12 chars" and
    # "rotate 90d", which is a sentence taken apart rather than two facts.
    policy = cell("auth:password")
    if policy:
        mapped = lookup("mfa", policy)
        if mapped:
            controls.append(mapped)
        elif normalise(policy) in ("no", "khong", "none", "false", "0"):
            controls.append("PASSWORD_ONLY")
        else:
            # This tenant declares no field for password policy. Kept verbatim in the description
            # rather than folded into a controls list, which would record a different fact — a
            # policy is not a control, and `authentication_controls` enumerates controls.
            unknown_auth.append(f"{columns.get('auth:password', 'Password Policy')}={policy}")

    if unknown_auth:
        notes.extend(unknown_auth)
    if controls:
        attributes["authentication_controls"] = sorted(set(controls))

    waf_raw = cell("attr:waf")
    if waf_raw:
        mapped = lookup("waf", waf_raw)
        if not mapped:
            warnings.append(f"WAF {waf_raw!r} is not in the tenant vocabulary; recorded as OTHER "
                            f"and the original kept in the description")
            notes.append(f"WAF={waf_raw}")
            mapped = "OTHER"
        attributes["waf"] = mapped

    abuse, unknown_abuse = [], []
    for value in split_values(cell("attr:abuse_controls")):
        mapped = lookup("abuse_controls", value)
        (abuse.append(mapped) if mapped else unknown_abuse.append(value))
    if unknown_abuse:
        warnings.append(f"abuse control not in the vocabulary: {', '.join(unknown_abuse)}")
    if abuse:
        attributes["abuse_controls"] = sorted(set(abuse))

    tier = None
    criticality_raw = cell("criticality")
    if criticality_raw:
        code = lookup("criticality", criticality_raw)
        if not code or code not in tenant["tiers"]:
            raise Problem(f"business criticality {criticality_raw!r} does not map to a tier this "
                          f"tenant declares ({', '.join(sorted(tenant['tiers']))})")
        tier = tenant["tiers"][code]

    contact_raw = cell("contact")
    contact_id = tenant["people_by_name"].get(normalise(contact_raw)) if contact_raw else None
    if contact_raw and not contact_id:
        # Not a failure: a techlead who has no account yet is a real state, and the name is worth
        # keeping. `delivery_team` is text and takes it.
        attributes["delivery_team"] = contact_raw
        warnings.append(f"owner {contact_raw!r} has no account; recorded as delivery_team text "
                        f"rather than as the named contact")

    domains = {}
    for target, header in columns.items():
        if target.startswith("domain:"):
            value = (row.get(header) or "").strip()
            if value:
                domains[target.split(":", 1)[1]] = value

    for header in columns.get("__unmapped__", []):
        value = (row.get(header) or "").strip()
        if not value:
            continue
        if unmapped_policy == "note":
            notes.append(f"{header}={value}")
        elif unmapped_policy == "fail":
            raise Problem(f"column {header!r} has no target field and holds {value!r}")

    if notes:
        attributes["description"] = (
            (attributes.get("description", "") + "\n\n" if attributes.get("description") else "")
            + "Recorded from the import spreadsheet: " + " · ".join(notes))

    return {
        "application": cell("application"),
        "project": project_name,
        "org": org,
        "exposure": exposure,
        "tier": tier,
        "contact_id": contact_id,
        "repository": cell("repository"),
        "attributes": attributes,
        "domains": domains,
        "warnings": warnings,
    }


# ==================================================================================================
# The tenant's own vocabulary, read once
# ==================================================================================================

#: Which permission each lookup needs. A 404 on a read is what missing permission looks like here,
#: so the failure names the permission rather than leaving somebody to infer it from "not found".
NEEDS = {
    "/api/v1/asset-types": "ast.assettype.read",
    "/api/v1/org-nodes": "org.node.read",
    "/api/v1/criticality-tiers": "org.nodetype.read",
    "/api/ui/people": "asm.request.read",
    "/api/ui/settings/fields": "ast.asset.read",
}


def _read(api: Aspm, path: str, collection: bool = True, **query):
    try:
        return api.all_rows(path, **query) if collection else api.get(path, **query)
    except ApiError as failure:
        raise SystemExit(
            f"\n  {failure}\n"
            f"  This lookup needs `{NEEDS.get(path, '?')}`. A 404 on a read is also what missing\n"
            f"  permission looks like — the platform does not distinguish it from a missing object —\n"
            f"  so check the credential AND the principal behind it before looking anywhere else."
        ) from None


def load_tenant(api: Aspm) -> dict:
    types = {t["code"]: t["id"] for t in _read(api, "/api/v1/asset-types")}
    for required in ("APPLICATION", "PROJECT"):
        if required not in types:
            raise SystemExit(f"this tenant has no {required} asset type. Seed it first.")

    nodes = _read(api, "/api/v1/org-nodes")
    org_by_name, names = {}, set()
    for node in nodes:
        org_by_name[normalise(node.get("name"))] = node
        names.add(node.get("name"))

    people = {}
    try:
        payload = api.get("/api/ui/people") or {}   # optional: absence is reported per row
        listing = payload.get("people", payload) if isinstance(payload, dict) else payload
        for person in listing or []:
            if not isinstance(person, dict):
                continue
            # The interface renders "Display Name  ·  username" in one field, so both halves are
            # indexed: a spreadsheet naming either one should resolve.
            labels = [person.get("name"), person.get("username"), person.get("email")]
            for part in re.split(r"\s*·\s*", person.get("name") or ""):
                labels.append(part)
            for key in labels:
                if key and key.strip():
                    people.setdefault(normalise(key), person.get("id"))
    except ApiError:
        pass  # reported per row as "no account", which is the honest outcome

    tiers = {t["code"]: t["id"] for t in _read(api, "/api/v1/criticality-tiers")}

    catalogue = {}
    try:
        catalogue = {f["key"]: f for f in
                     (api.get("/api/ui/settings/fields", type="PROJECT") or {}).get("fields", [])}
    except ApiError:
        pass

    return {"types": types, "org_by_name": org_by_name, "org_names": names,
            "people_by_name": people, "tiers": tiers, "catalogue": catalogue}


def check_catalogue(plans: list, tenant: dict) -> list:
    """Every attribute the plan wants must be a field this tenant declares, with a permitted value."""
    problems = []
    for index, plan in plans:
        for key, value in (plan or {}).get("attributes", {}).items():
            field = tenant["catalogue"].get(key)
            if field is None:
                problems.append(f"row {index}: this tenant declares no PROJECT field {key!r}")
                continue
            permitted = field.get("permittedValues") or []
            if permitted:
                for one in (value if isinstance(value, list) else [value]):
                    if one not in permitted:
                        problems.append(f"row {index}: {key}={one!r} is not permitted "
                                        f"({', '.join(permitted)})")
    return problems


# ==================================================================================================
# Applying
# ==================================================================================================

def find_asset(api: Aspm, name: str, type_id: str, cache: dict) -> dict | None:
    if type_id not in cache:
        cache[type_id] = {(_r.get("display_name") or "").strip().casefold(): _r
                          for _r in api.all_rows("/api/v1/assets", type_id=type_id)}
    return cache[type_id].get(name.strip().casefold())


def apply_plan(api: Aspm, plan: dict, tenant: dict, cache: dict) -> dict:
    outcome = {"application": None, "project": None, "created": [], "edge_missing": False}

    if plan["application"]:
        app = find_asset(api, plan["application"], tenant["types"]["APPLICATION"], cache)
        if not app:
            app = api.post("/api/v1/assets", {
                "type_id": tenant["types"]["APPLICATION"],
                "display_name": plan["application"],
                "owning_node_id": plan["org"]["id"],
                "criticality_mode": "INHERITED",
            })
            cache[tenant["types"]["APPLICATION"]][plan["application"].casefold()] = app
            outcome["created"].append("application")
        outcome["application"] = app["id"]

    project = find_asset(api, plan["project"], tenant["types"]["PROJECT"], cache)
    if not project:
        body = {"type_id": tenant["types"]["PROJECT"], "display_name": plan["project"],
                "owning_node_id": plan["org"]["id"], "criticality_mode": "INHERITED"}
        if plan["exposure"]:
            body["exposure_declared"] = plan["exposure"]
        project = api.post("/api/v1/assets", body)
        cache[tenant["types"]["PROJECT"]][plan["project"].casefold()] = project
        outcome["created"].append("project")
    outcome["project"] = project["id"]

    # Everything the versioned path cannot carry: declared fields, endpoints, the contact, the tier.
    # Read-modify-write, because this endpoint is FULL REPLACE — a field it carries and we omit is
    # cleared. See 07_update_record.py.
    record = api.get(f"/api/ui/projects/{project['id']}/editor")
    current = record["project"]
    attributes = dict(current.get("attributes") or {})
    attributes.update(plan["attributes"])
    api.post(f"/api/ui/projects/{project['id']}/editor", {
        "name": plan["project"],
        "rowVersion": current["rowVersion"],
        "criticalityTierId": plan["tier"] or _existing_tier(current, record),
        "exposureDeclared": plan["exposure"] or current.get("exposureDeclared"),
        "technicalContactId": plan["contact_id"] or current.get("technicalContactId"),
        "attributes": attributes,
        "repository": plan["repository"] or current.get("repository", ""),
        "repositoryBranch": current.get("repositoryBranch", ""),
        "domains": plan["domains"],
    })

    outcome["edge_missing"] = bool(plan["application"])
    return outcome


def _existing_tier(current: dict, record: dict):
    """Preserve an assigned tier: null on this endpoint means INHERITED, not 'unchanged'."""
    if current.get("criticalityInherited"):
        return None
    code = current.get("criticalityCode")
    tiers = {t["code"]: t["id"] for t in record.get("tiers", [])}
    return tiers.get(code)


def link_sql(pairs: list) -> str:
    lines = [
        "-- Attach each imported service to its product.",
        "--",
        "-- THIS BYPASSES THE DOMAIN LAYER, and that is why the importer does not run it. No API",
        "-- creates a CONTAINS edge between an application and a project: the project editor states",
        "-- that it deliberately cannot, and the SBOM door attaches only the artifact it creates. So",
        "-- these statements write asset_relationship directly, with none of the checks a service",
        "-- method would apply. Read them before running them, and run them as the migration role.",
        "--",
        "-- Idempotent: an edge that already exists is not written twice.",
        "",
        "BEGIN;",
        "SET LOCAL aspm.current_tenant = :'tenant_id';",
        "",
    ]
    for application_id, project_id, label in pairs:
        lines.append(f"-- {label}")
        lines.append(
            "INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,\n"
            "                                discovery_source, attributes, valid_from)\n"
            f"SELECT current_tenant_id(), '{application_id}', '{project_id}', 'CONTAINS',\n"
            "       'MANUAL', '{\"created_by\":\"inventory-import\"}'::jsonb, now()\n"
            " WHERE NOT EXISTS (SELECT 1 FROM asset_relationship r\n"
            f"                   WHERE r.from_asset_id = '{application_id}'\n"
            f"                     AND r.to_asset_id = '{project_id}'\n"
            "                     AND r.edge_type = 'CONTAINS' AND r.valid_until IS NULL);")
        lines.append("")
    lines.append("COMMIT;")
    return "\n".join(lines)


# ==================================================================================================

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("workbook")
    parser.add_argument("--sheet", help="sheet name; the first sheet by default")
    parser.add_argument("--apply", action="store_true", help="write. Without it, nothing is sent")
    parser.add_argument("--unmapped", choices=["fail", "note", "ignore"], default="fail",
                        help="a column with no target field (default: fail)")
    parser.add_argument("--emit-link-sql", metavar="FILE",
                        help="write the product->service edge statements here, unexecuted")
    parser.add_argument("--limit", type=int, help="process only the first N rows")
    args = parser.parse_args()

    rows = read_xlsx(args.workbook, args.sheet)
    if args.limit:
        rows = rows[:args.limit]
    if not rows:
        raise SystemExit("no data rows under the header")

    headers = list(rows[0].keys())
    columns, matched = {}, set()
    for aliases, target, _note in MAPPING:
        for header in headers:
            if normalise(header) in aliases and target not in columns:
                columns[target] = header
                matched.add(header)
                break
    columns["__unmapped__"] = [h for h in headers if h not in matched]

    print(f"\n  {args.workbook} · {len(rows)} row(s) · {len(headers)} column(s)\n")
    print("  column mapping")
    for aliases, target, note in MAPPING:
        header = columns.get(target)
        mark = "→" if header else "·"
        print(f"    {mark} {(header or '(absent from the sheet)'):<34} {target}"
              + (f"   {note}" if note else ""))
    if columns["__unmapped__"]:
        print(f"\n  {len(columns['__unmapped__'])} column(s) with no target field: "
              f"{', '.join(columns['__unmapped__'])}")
        print(f"  --unmapped is '{args.unmapped}'"
              + ("  → these rows will be refused" if args.unmapped == "fail" else
                 "  → appended to the description" if args.unmapped == "note" else
                 "  → discarded"))

    api = Aspm.from_environment()
    try:
        tenant = load_tenant(api)
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        return 1

    plans, failures = [], []
    for index, row in enumerate(rows, start=2):
        try:
            plans.append((index, plan_row(row, columns, tenant, args.unmapped)))
        except Problem as problem:
            failures.append(f"row {index}: {problem}")

    failures += check_catalogue(plans, tenant)

    print(f"\n  {len(plans)} row(s) resolve · {len(failures)} refused")
    for message in failures:
        print(f"    ✗ {message}")
    warned = [(i, p) for i, p in plans if p["warnings"]]
    if warned:
        print(f"\n  {len(warned)} row(s) with something worth reading:")
        for index, plan in warned:
            for warning in plan["warnings"]:
                print(f"    ! row {index} ({plan['project']}): {warning}")

    if not args.apply:
        print("\n  Nothing was sent. This was a validation pass.")
        if plans:
            index, sample = plans[0]
            print(f"\n  row {index} would become:")
            print("   " + json.dumps({
                "application": sample["application"], "project": sample["project"],
                "org": sample["org"].get("name"), "exposure": sample["exposure"],
                "attributes": sample["attributes"], "domains": sample["domains"],
                "repository": sample["repository"]}, indent=2, ensure_ascii=False
            ).replace("\n", "\n   "))
        print("\n  Re-run with --apply to write. Fix the refusals first: a refused row is a row")
        print("  whose data the platform would have had to guess at.")
        return 1 if failures else 0

    if failures:
        raise SystemExit("\n  Refusing to apply while rows are refused. Fix them, or drop them from "
                         "the sheet:\n    " + "\n    ".join(failures[:10]))

    cache, links, applied = {}, [], 0
    for index, plan in plans:
        try:
            outcome = apply_plan(api, plan, tenant, cache)
            applied += 1
            created = "+".join(outcome["created"]) or "updated"
            print(f"    row {index}  {plan['project'][:38]:<40} {created}")
            if outcome["edge_missing"] and outcome["application"]:
                links.append((outcome["application"], outcome["project"],
                              f"{plan['application']} contains {plan['project']}"))
        except ApiError as failure:
            print(f"    row {index}  {plan['project'][:38]:<40} FAILED: {failure}", file=sys.stderr)

    print(f"\n  {applied} of {len(plans)} row(s) written.")
    if links:
        print(f"\n  ⚠ {len(links)} service(s) have NO application above them. No API creates that")
        print("    edge, so they will show an empty application column and will not roll up.")
        if args.emit_link_sql:
            with open(args.emit_link_sql, "w", encoding="utf-8") as handle:
                handle.write(link_sql(links))
            print(f"    Statements written to {args.emit_link_sql} — read them, then run as the")
            print("    migration role with -v tenant_id=<your tenant>. This script does not run them.")
        else:
            print("    Pass --emit-link-sql FILE to have the statements written out.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
