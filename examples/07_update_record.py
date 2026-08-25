#!/usr/bin/env python3
"""
Update SOME fields on an application or project that already exists.

    python3 07_update_record.py application <id> --exposure INTERNET_PUBLIC
    python3 07_update_record.py application <id> --name "Payments API" --tags pci,payments
    python3 07_update_record.py project <id> --exposure INTERNAL_ONLY --contact <principal-id>
    python3 07_update_record.py application <id> --domain UAT=uat-pay.internal.example
    python3 07_update_record.py project <id> --dry-run --exposure PARTNER_B2B

THREE UPDATE PATHS, THREE DIFFERENT SEMANTICS. THIS IS THE WHOLE POINT OF THIS SCRIPT.
-------------------------------------------------------------------------------------
Measured on a live deployment, not read off the specification:

**1. `PATCH /api/v1/assets/{id}` — a true partial update, over a very small field set.**
Send `display_name` alone and only `display_name` and `row_version` change; nothing else is touched.
But only three fields are writable on update:

    display_name · owning_node_id · lifecycle_state

Anything else is a 400 naming it — including `exposure_declared`, which is writable on CREATE and
not on update, and `criticality_tier_id`, which is not writable over this path at all:

    PATCH {"exposure_declared": "PARTNER_B2B"}
    → 400 VALIDATION_FAILED: unknown field(s) [exposure_declared]

A stale `row_version` on this path returns **404**, not 409 — indistinguishable from a missing
object. That is not the same behaviour as the editor path below, which answers 409 STALE.

**2. The editor endpoints — everything else, and they are FULL REPLACE.**
`POST /api/ui/applications/{id}` and `POST /api/ui/projects/{id}/editor` carry exposure, criticality,
the contact, the declared fields, the tags and the endpoints. A field they carry and you omit is
**cleared**. Measured:

    POST /api/ui/applications/{id}  {"name": …, "owningNodeId": …, "exposureDeclared": "PARTNER_B2B",
                                     "rowVersion": 1}
    → 200, and description, userBase, features and tags are now all empty

    POST /api/ui/projects/{id}/editor  {"name": …, "rowVersion": …,
                                        "attributes": {"delivery_team": "…"}}
    → 200, and the project's `description` — which nobody mentioned — is gone

HTTP 200. No warning. The field is simply not there any more. So **never hand-build one of these
bodies**: read the record, change what you meant to change, send the whole thing back. That is what
this script does, and it is the only safe shape.

**3. `domains` is the exception — it is per-environment partial.**
An environment absent from `domains` keeps its endpoints; an environment present with an empty value
has them cleared. Three semantics in one payload, so the distinction is worth stating rather than
discovering.

WHAT --dry-run IS FOR
---------------------
It prints the body that would be sent and the fields that would change, and sends nothing. On a
full-replace endpoint that is not a convenience, it is how you check that a read-modify-write did not
drop something before you find out from the audit trail.
"""

import argparse
import json
import sys

from aspm_client import Aspm, ApiError

EXPOSURES = ["INTERNET_PUBLIC", "PARTNER_B2B", "INTERNAL_ONLY", "AIR_GAPPED"]


def load(api: Aspm, kind: str, record_id: str) -> tuple:
    """The record as the editor sees it, plus the option lists that came with it."""
    if kind == "application":
        body = api.get(f"/api/ui/applications/{record_id}/editor")
        record = body.get("application")
        if record is None:
            raise SystemExit(f"no application {record_id} this credential can reach")
        return record, body, f"/api/ui/applications/{record_id}"
    body = api.get(f"/api/ui/projects/{record_id}/editor")
    return body["project"], body, f"/api/ui/projects/{record_id}/editor"


def resolve_tier(record: dict, envelope: dict, chosen_code: str | None) -> str | None:
    """
    The tier id to send, and getting this wrong is a silent change nobody would notice.

    *** THIS RETURNED None AND THAT WAS A DEFECT. *** The editor payload carries the tier CODE and
    the save wants the tier ID, so "leave it alone" looked like "send null". It is not: null means
    INHERITED, so an application with an ASSIGNED tier would have been quietly converted to
    following its organization — a change nobody made, on a value the DOC-28 risk model scores.
    Caught by --dry-run before it was ever sent.

    So the code is resolved back to an id through the option list the editor supplied.
    """
    tiers = {t["code"]: t["id"] for t in envelope.get("tiers", [])}
    if chosen_code:
        if chosen_code not in tiers:
            raise SystemExit(f"no criticality tier '{chosen_code}'. Declared: "
                             + ", ".join(sorted(tiers)))
        return tiers[chosen_code]
    if record.get("criticalityInherited"):
        return None
    current = record.get("criticalityCode")
    if current and current in tiers:
        return tiers[current]
    if current:
        raise SystemExit(
            f"this record has the assigned tier '{current}', which is not in the editor's tier "
            "list, so it cannot be round-tripped. Refusing rather than sending null, which would "
            "convert it to inherited.")
    return None


def application_body(record: dict, args, tier_id: str | None) -> dict:
    """
    Everything the application editor carries, with the caller's changes applied.

    Every key is present even when unchanged. That is not verbosity — it is the difference between
    an update and a deletion on this endpoint.
    """
    return {
        "name": args.name or record["name"],
        "owningNodeId": args.owner or record.get("owningNodeId"),
        "criticalityTierId": tier_id,
        "exposureDeclared": args.exposure or record.get("exposureDeclared"),
        "description": _pick(args.description, record.get("description")),
        "userBase": _pick(args.user_base, record.get("userBase")),
        "features": _pick(args.features, record.get("features")),
        "tags": _pick(args.tags, record.get("tags")),
        "repository": _pick(args.repository, record.get("repository")),
        "domains": _domains(args, record),
        "rowVersion": record["rowVersion"],
    }


def project_body(record: dict, args, tier_id: str | None) -> dict:
    attributes = dict(record.get("attributes") or {})
    return {
        "name": args.name or record["name"],
        "criticalityTierId": tier_id,
        "exposureDeclared": args.exposure or record.get("exposureDeclared"),
        "technicalContactId": args.contact or record.get("technicalContactId"),
        # Read back and re-sent whole. Sending only the keys you changed blanks the rest.
        "attributes": attributes,
        "repository": _pick(args.repository, record.get("repository")),
        "repositoryBranch": _pick(args.branch, record.get("repositoryBranch")),
        "domains": _domains(args, record),
        "rowVersion": record["rowVersion"],
    }


def _pick(new, current):
    """The caller's value if they gave one, otherwise what is already there. Never None."""
    return new if new is not None else (current or "")


def _domains(args, record: dict) -> dict:
    """
    Only the environments the caller named. Everything else keeps what it has, because this is the
    one part of the payload the server treats as partial.
    """
    out = {}
    for assignment in args.domain:
        if "=" not in assignment:
            raise SystemExit(f"--domain expects CODE=host[,host], got '{assignment}'")
        code, hosts = assignment.split("=", 1)
        out[code.upper()] = hosts
    return out


def changes(before: dict, after: dict, tier_codes: dict) -> dict:
    """What this write would actually alter, as the editor's own field names."""
    interesting = ("name", "exposureDeclared", "technicalContactId", "owningNodeId",
                   "description", "userBase", "features", "tags", "repository",
                   "repositoryBranch")
    delta = {}
    # Criticality is compared as a CODE against a CODE. The body carries an id, so a naive
    # comparison would report a change on every write, or none.
    if "criticalityTierId" in after:
        was_code = before.get("criticalityCode") if not before.get("criticalityInherited") else None
        now_code = tier_codes.get(after["criticalityTierId"]) if after["criticalityTierId"] else None
        if (was_code or "INHERITED") != (now_code or "INHERITED"):
            delta["criticality"] = [was_code or "INHERITED", now_code or "INHERITED"]
    for key in interesting:
        if key not in after:
            continue
        was = before.get(key)
        now = after.get(key)
        if (was or "") != (now or ""):
            delta[key] = [was, now]
    if after.get("domains"):
        delta["domains"] = [{k: v for k, v in (before.get("domains") or {}).items()
                             if k in after["domains"]}, after["domains"]]
    return delta


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("kind", choices=["application", "project"])
    parser.add_argument("id")
    parser.add_argument("--name")
    parser.add_argument("--exposure", choices=EXPOSURES)
    parser.add_argument("--criticality", help="criticality tier CODE, e.g. TIER1")
    parser.add_argument("--owner", help="owning org node id — applications only")
    parser.add_argument("--contact", help="technical contact principal id — projects only")
    parser.add_argument("--description")
    parser.add_argument("--user-base", help="applications only")
    parser.add_argument("--features", help="applications only, comma-separated")
    parser.add_argument("--tags", help="applications only, comma-separated")
    parser.add_argument("--repository", help="a reference, never a clone")
    parser.add_argument("--branch", help="projects only")
    parser.add_argument("--domain", action="append", default=[], metavar="CODE=host[,host]",
                        help="repeatable; an environment you do not name keeps what it has")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the body and the diff, send nothing")
    args = parser.parse_args()

    api = Aspm.from_environment()
    try:
        record, envelope, path = load(api, args.kind, args.id)

        tier_id = resolve_tier(record, envelope, args.criticality)
        tier_codes = {t["id"]: t["code"] for t in envelope.get("tiers", [])}

        body = (application_body(record, args, tier_id) if args.kind == "application"
                else project_body(record, args, tier_id))

        delta = changes(record, body, tier_codes)
        print(f"\n  {record['name']}  (rowVersion {record['rowVersion']})")
        if not delta:
            print("\n  Nothing would change. Not sending: a write that changes nothing still bumps"
                  "\n  the row version and still lands in the audit trail as an edit somebody made.")
            return 0
        print("\n  would change:")
        for key, (was, now) in delta.items():
            print(f"    {key}")
            print(f"      from {json.dumps(was)}")
            print(f"      to   {json.dumps(now)}")

        if args.dry_run:
            print("\n  --dry-run: nothing sent. The full body that would be posted:\n")
            print("   " + json.dumps(body, indent=2).replace("\n", "\n   "))
            return 0

        api.post(path, body)
        record_after, _, _ = load(api, args.kind, args.id)
        print(f"\n  written. rowVersion {record['rowVersion']} -> {record_after['rowVersion']}")
        return 0
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        if failure.status == 409:
            print("\n  Somebody else edited this record while you were reading it. Re-read and"
                  "\n  re-apply — the write was refused rather than overwriting their edit.",
                  file=sys.stderr)
        if failure.status == 404:
            print("\n  Also what insufficient permission looks like: the platform does not"
                  "\n  distinguish 'no such object' from 'not yours'. Check ast.asset.update on the"
                  "\n  credential AND on the principal behind it.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
