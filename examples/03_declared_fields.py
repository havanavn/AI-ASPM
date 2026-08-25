#!/usr/bin/env python3
"""
The tenant's own fields: find out what they are called, then write them.

    python3 03_declared_fields.py catalogue --type PROJECT
    python3 03_declared_fields.py environments
    python3 03_declared_fields.py show <project-id>
    python3 03_declared_fields.py set <project-id> --field delivery_team="Payments Platform" \\
                                                   --field tech_stack=JAVA,SPRING \\
                                                   --domain UAT=uat-pay.internal.example
    python3 03_declared_fields.py set <project-id> --domain UAT=      # clears UAT's endpoints

WHAT A FIELD IS CALLED
----------------------
The name is the `attribute_key` typed when the field was declared — lower case, underscores,
matching `^[a-z][a-z0-9_]{1,48}$`. It is NOT derived from the label: a field labelled "Who uses it"
has the key `user_base`. `catalogue` prints the mapping, which is the answer to "what is this
variable called in the API".

The same key appears in three places with three prefixes, and mixing them up is the usual mistake:

    attributes.<key>       in the body of a write, here
    attr.<key>             in a query string when filtering a list
    asset.attributes.<key> in the database

Endpoint environments work the same way but the code is upper case (`^[A-Z][A-Z0-9_]{1,30}$`) and
the body key is `domains.<CODE>`; when filtering, `host.<CODE>` and `hostState.<CODE>`.

⚠ THE TRAP: A MISSPELLED FIELD KEY IS ACCEPTED AND DISCARDED
------------------------------------------------------------
This endpoint reads only keys the catalogue declares. `delivery_teams` when the field is
`delivery_team` returns **200 with nothing written**. That is the opposite of the versioned API,
which refuses an unknown field with a 400 naming it, and it is a real inconsistency in the platform
rather than something this script can fix.

So this script validates every key against the catalogue BEFORE sending, and refuses locally. An
unknown environment code IS refused by the server (`ENVIRONMENT_UNKNOWN`), so that one is safe
either way — the asymmetry is only on declared fields.

⚠ SAVING RE-SENDS THE REPOSITORY, AND THE PLATFORM MAY DUPLICATE IT
-------------------------------------------------------------------
`set` has to re-send `repository` and `repositoryBranch` exactly as the GET returned them, because
the save closes the project's BUILDS edge when they are absent — omitting them CLEARS the repository.

Re-sending is not free either. The GET returns the repository asset's **display name**, and the save
resolves a name to an asset by an identity rule that does not always produce the identity key the
asset was created with. Measured on this deployment: a project whose repository asset had the
identity key `repo:card-authorization-platform-api` and the display name
`card-authorization-platform-api` acquired a SECOND repository asset keyed
`card-authorization-platform-api` on save, and its BUILDS edge moved to the new one. The same estate
already carried the fingerprint of this happening before, three times over, through the interface:

    repo:Card Issuing/Authorization/aspm-upload-check
    repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/aspm-upload-check
    repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/repo:…/aspm-upload-check

Two writers, two identity rules, one asset type — which is what ADR-009's identity rule per type
exists to prevent, and the result is duplicate repositories the graph treats as different things.

So: `set` REFUSES to run on a project that has a repository recorded unless you pass
`--repository <name>` or `--accept-repository-risk`. It cannot fix the platform, and silently
duplicating an asset is worse than stopping.

THIS IS THE INTERFACE'S OWN ENDPOINT, NOT A VERSIONED API
---------------------------------------------------------
`/api/ui/...` is what the browser calls. It is the only path that writes declared fields, and it
carries no compatibility promise: it can change shape between builds without that counting as a
breaking change. Pin the platform version you tested against if you automate against it.
"""

import argparse
import sys

from aspm_client import Aspm, ApiError, print_table


def catalogue(api: Aspm, type_code: str) -> list:
    body = api.get("/api/ui/settings/fields", type=type_code)
    fields = body.get("fields", [])
    print(f"\n  {len(fields)} declared field(s) on {body.get('selectedType') or type_code}\n")
    print_table(
        [{"key": f["key"], "dataType": f["dataType"], "filterable": f["filterable"],
          "required": f["required"], "label": f["label"],
          "permittedValues": ", ".join(f.get("permittedValues") or [])[:44]} for f in fields],
        ["key", "dataType", "filterable", "required", "label", "permittedValues"])
    print("\n  Value shape per dataType when you write it:")
    print("    MULTI_SELECT -> JSON array      BOOLEAN -> true/false")
    print("    INTEGER      -> JSON number     everything else -> string")
    return fields


def environments(api: Aspm) -> list:
    body = api.get("/api/ui/settings/environments")
    rows = body.get("environments", [])
    print(f"\n  {len(rows)} endpoint environment(s)\n")
    print_table(rows, ["code", "label", "lifecycleState", "declared", "endpointCount"])
    print("\n  lifecycleState: ACTIVE offered in forms · DEPRECATED retired but data kept ·")
    print("                  UNDECLARED present only because recorded data carries it")
    if rows and rows[0].get("endpointCount") is None:
        print("\n  endpointCount is absent, not zero: it is tenant-wide and withheld from a reader")
        print("  without cfg.asset.field.manage rather than narrowed to their scope.")
    return rows


def show(api: Aspm, project_id: str) -> None:
    record = api.get(f"/api/ui/projects/{project_id}/editor")
    project = record["project"]
    print(f"\n  {project['name']}  ({project_id})")
    print(f"  rowVersion {project['rowVersion']}\n")
    values = project.get("attributes") or {}
    fields = {f["key"]: f for f in record.get("fields", [])}
    rows = []
    for key, field in fields.items():
        value = values.get(key)
        rows.append({
            "key": key, "dataType": field["dataType"],
            # "not recorded" rather than blank. A blank cell reads as a value; the two are
            # different answers and the platform draws them apart everywhere else too.
            "value": "not recorded" if value in (None, "", []) else (
                ", ".join(map(str, value)) if isinstance(value, list) else str(value)),
        })
    print_table(rows, ["key", "dataType", "value"])
    print("\n  endpoints by environment:")
    domains = project.get("domains") or {}
    for env in record.get("environments", []):
        hosts = domains.get(env["code"]) or []
        state = "" if env["lifecycleState"] == "ACTIVE" else f"  [{env['lifecycleState']}]"
        print(f"    {env['code']:<12} {', '.join(hosts) if hosts else 'none recorded'}{state}")


def set_values(api: Aspm, project_id: str, assignments: list, domain_args: list,
               repository_override: str | None = None, accept_risk: bool = False) -> None:
    record = api.get(f"/api/ui/projects/{project_id}/editor")
    project = record["project"]
    declared = {f["key"]: f for f in record.get("fields", [])}
    known_environments = {e["code"] for e in record.get("environments", [])}

    attributes = dict(project.get("attributes") or {})
    for assignment in assignments:
        if "=" not in assignment:
            raise SystemExit(f"--field expects key=value, got '{assignment}'")
        key, raw = assignment.split("=", 1)
        field = declared.get(key)
        if field is None:
            # Refused HERE, because the server would accept this and write nothing.
            raise SystemExit(
                f"'{key}' is not a declared field on this type. The server would return 200 and\n"
                f"  write nothing. Declared keys: {', '.join(sorted(declared))}")
        attributes[key] = coerce(field, raw)

    domains = {}
    for assignment in domain_args:
        if "=" not in assignment:
            raise SystemExit(f"--domain expects CODE=host[,host], got '{assignment}'")
        code, raw = assignment.split("=", 1)
        code = code.upper()
        if code not in known_environments:
            raise SystemExit(
                f"'{code}' is not an environment this tenant declares. Declared: "
                f"{', '.join(sorted(known_environments))}\n"
                "  Declare it at Configuration -> Asset fields -> Endpoint environments.")
        # An empty value is how an environment's endpoints are cleared, and it is NOT the same as
        # omitting the key: an environment absent from the body is left exactly as it is.
        domains[code] = raw

    recorded_repository = project.get("repository") or ""
    if recorded_repository and not (repository_override or accept_risk):
        raise SystemExit(
            f"this project records the repository '{recorded_repository}'.\n"
            "  Saving re-sends it, and the platform may create a SECOND repository asset for it —\n"
            "  see the module docstring. Omitting it instead would CLEAR the repository edge.\n"
            "  Neither is safe to do silently, so choose:\n"
            f"    --repository '{recorded_repository}'   re-send it and accept the risk\n"
            "    --accept-repository-risk              same, without retyping the name")

    body = {
        "name": project["name"],
        "rowVersion": project["rowVersion"],
        # *** THIS SENT None UNCONDITIONALLY AND THAT WAS A DEFECT. ***
        #
        # null means INHERITED on this endpoint, so a project with an ASSIGNED tier was silently
        # converted to following its organization on every write — a change nobody made, on the value
        # the DOC-28 risk model scores. It happened to a real record during verification and was only
        # caught by diffing the database against the seed.
        #
        # The editor payload carries the tier CODE and the save wants the tier ID, so the code is
        # resolved back through the option list the same response supplied.
        "criticalityTierId": _tier_id(project, record),
        "exposureDeclared": project.get("exposureDeclared"),
        "technicalContactId": project.get("technicalContactId"),
        "attributes": attributes,
        "repository": repository_override or recorded_repository,
        "repositoryBranch": project.get("repositoryBranch", ""),
    }
    if domains:
        body["domains"] = domains

    api.post(f"/api/ui/projects/{project_id}/editor", body)
    print(f"\n  written. rowVersion was {project['rowVersion']}; re-read to get the new one.")
    show(api, project_id)


def _tier_id(project: dict, record: dict) -> str | None:
    """
    The criticality tier to send back unchanged, or None where it is genuinely inherited.

    Refuses rather than guessing if an assigned code is not in the option list: sending None would
    convert an assigned tier to inherited, and that is a change to a scored value.
    """
    if project.get("criticalityInherited"):
        return None
    code = project.get("criticalityCode")
    if not code:
        return None
    tiers = {t["code"]: t["id"] for t in record.get("tiers", [])}
    if code not in tiers:
        raise SystemExit(
            f"this project has the assigned criticality '{code}', which is not in the editor's tier "
            "list, so it cannot be sent back unchanged. Refusing: sending null would convert it to "
            "inherited.")
    return tiers[code]


def coerce(field: dict, raw: str):
    """Turn a command-line string into the shape the field's dataType stores."""
    kind = field["dataType"]
    if kind == "MULTI_SELECT":
        values = [v.strip() for v in raw.split(",") if v.strip()]
        permitted = field.get("permittedValues") or []
        unknown = [v for v in values if permitted and v not in permitted]
        if unknown:
            raise SystemExit(f"{field['key']}: {', '.join(unknown)} not in "
                             f"{', '.join(permitted)}")
        return values
    if kind == "SINGLE_SELECT":
        permitted = field.get("permittedValues") or []
        if permitted and raw not in permitted:
            raise SystemExit(f"{field['key']}: '{raw}' not in {', '.join(permitted)}")
        return raw
    if kind == "BOOLEAN":
        return raw.strip().lower() in ("true", "yes", "1", "on")
    if kind == "INTEGER":
        if raw.strip() == "":
            return None
        try:
            return int(raw)
        except ValueError:
            raise SystemExit(f"{field['key']}: '{raw}' is not a whole number") from None
    return raw


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    cat = sub.add_parser("catalogue", help="what the fields are called")
    cat.add_argument("--type", default="PROJECT", help="asset type code (default PROJECT)")

    sub.add_parser("environments", help="the endpoint environment vocabulary")

    showing = sub.add_parser("show", help="one project's declared fields and endpoints")
    showing.add_argument("project_id")

    setting = sub.add_parser("set", help="write declared fields and endpoints")
    setting.add_argument("project_id")
    setting.add_argument("--field", action="append", default=[], metavar="key=value")
    setting.add_argument("--domain", action="append", default=[], metavar="CODE=host[,host]")
    setting.add_argument("--repository", help="the repository name to record; see the docstring")
    setting.add_argument("--accept-repository-risk", action="store_true",
                         help="re-send the recorded repository name unchanged, accepting that the "
                              "platform may duplicate the repository asset")

    args = parser.parse_args()
    api = Aspm.from_environment()
    try:
        if args.command == "catalogue":
            catalogue(api, args.type)
        elif args.command == "environments":
            environments(api)
        elif args.command == "show":
            show(api, args.project_id)
        else:
            if not args.field and not args.domain:
                raise SystemExit("nothing to write: pass --field and/or --domain")
            set_values(api, args.project_id, args.field, args.domain,
                       args.repository, args.accept_repository_risk)
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
