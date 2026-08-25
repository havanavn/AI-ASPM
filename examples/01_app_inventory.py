#!/usr/bin/env python3
"""
Application inventory: list it, and add to it.

    python3 01_app_inventory.py list
    python3 01_app_inventory.py list --type PROJECT
    python3 01_app_inventory.py create "Payments API" --type APPLICATION --owner <org-node-id>
    python3 01_app_inventory.py create "Payments API" --type APPLICATION --owner <id> --if-exists update

POSTING THE SAME RECORD TWICE DOES NOT OVERWRITE — IT FAILS, AND BADLY
----------------------------------------------------------------------
Identity is `(tenant, asset type, identity key)` where the identity key is the display name folded to
lower case, enforced by a unique index. So a second create of the same name is a constraint
violation. What comes back is **not** a 409 naming the existing record:

    POST /api/v1/assets {"display_name": "Payments API", …}   → 201
    POST /api/v1/assets {"display_name": "Payments API", …}   → 500 INTERNAL_ERROR + correlation id
    POST /api/v1/assets {"display_name": "payments api", …}   → 500  (identity is case-insensitive)

The dispatcher maps foreign-key violations to a clean refusal and does not map unique violations, so
a duplicate falls through to the generic handler. It is a real defect, and until it is fixed a 500 on
a create means "this already exists" far more often than it means anything else.

**And the retry will not save you.** `Idempotency-Key` is required on this endpoint and is not acted
on: the dispatcher validates its shape and executes the request anyway. Measured — the same key sent
twice executed twice. Only `POST /api/v1/finding-imports` implements a real replay check.

So `--if-exists` decides what this script does when the name is taken:

    fail    (default) stop, and say which record already has the name
    update  overwrite the fields this path can write, on the existing record
    skip    leave it alone and report that it was left alone

WHAT THIS PATH CAN AND CANNOT SET
---------------------------------
`/api/v1/assets` is the versioned door and it exposes a FIXED column set: name, type, owner,
criticality, exposure, lifecycle, tags. It does NOT accept `attributes` — the fields your tenant
declared at Configuration -> Asset fields — and it does not accept endpoint domains. Sending either
is refused with 400 and the field named, rather than accepted and dropped:

    VALIDATION_FAILED: unknown field(s) [attributes]

That refusal is the design (`PRD-API-020`): a silently ignored field means a typo produces a no-op
the client believes succeeded. `asset.attributes` is absent from this projection because it can hold
anything a tenant puts there, including material that needs an attribute-level permission the
platform does not yet have (`SEC-AUZ-022`) — so exposing it wholesale would return fields nobody
authorized individually.

To set declared fields or endpoint domains, use 03_declared_fields.py. Two calls, deliberately:
create the asset here, then fill in the tenant's own fields there.

PERMISSIONS: ast.asset.read to list, ast.asset.create to create, ast.asset.update to amend — held by
the credential AND by the principal behind it.
"""

import argparse
import sys

from aspm_client import Aspm, ApiError, print_table

COLUMNS = ["display_name", "lifecycle_state", "exposure_declared", "criticality_mode", "id"]


def type_id(api: Aspm, code: str) -> str:
    """Resolve an asset type code to its id. Types are tenant data, so the codes are yours."""
    types = api.all_rows("/api/v1/asset-types")
    for row in types:
        if row["code"] == code:
            return row["id"]
    raise SystemExit(
        f"no asset type '{code}' in this tenant. Declared types: "
        + ", ".join(sorted(r["code"] for r in types)))


def list_assets(api: Aspm, type_code: str | None, limit: int) -> None:
    filters = {}
    if type_code:
        filters["type_id"] = type_id(api, type_code)
    rows = api.all_rows("/api/v1/assets", limit=limit, **filters)
    print(f"\n  {len(rows)} asset(s)" + (f" of type {type_code}" if type_code else "") + "\n")
    print_table(rows, COLUMNS)
    # Absence is reported, not implied. An empty inventory and an inventory nobody has populated
    # look identical in a count, and only one of them is a finding.
    if not rows:
        print("\n  Nothing is recorded. That is not the same as nothing existing — if you expected"
              "\n  rows here, check the credential's scope: a scoped credential sees its subtree.")


def find_by_name(api: Aspm, name: str, type_code: str) -> dict | None:
    """
    The existing record with this name, or None.

    Matched the way the PLATFORM matches it — case-folded — rather than exactly, because that is what
    the unique index does and a lookup that disagreed with the constraint would report "not found"
    immediately before the insert failed on a duplicate.

    Over v1 because the interface search is a different contract; `display_name` is not filterable
    there (the API says so, with a 400), so this pages the type and matches locally. For a large
    estate, filter harder or keep your own index of what you have loaded.
    """
    wanted = name.strip().casefold()
    for row in api.all_rows("/api/v1/assets", type_id=type_id(api, type_code)):
        if (row.get("display_name") or "").strip().casefold() == wanted:
            return row
    return None


def create_asset(api: Aspm, name: str, type_code: str, owner: str,
                 exposure: str | None, criticality: str | None,
                 if_exists: str = "fail") -> None:
    existing = find_by_name(api, name, type_code)
    if existing:
        if if_exists == "skip":
            print(f"\n  {name} already exists as {existing['id']}. Left alone.")
            return
        if if_exists == "fail":
            raise SystemExit(
                f"\n  '{name}' already exists as {existing['id']}"
                f" (lifecycle {existing['lifecycle_state']}).\n"
                "  Creating it again would be refused by the identity constraint, and the refusal\n"
                "  arrives as a 500 rather than a conflict. Choose:\n"
                "    --if-exists update   overwrite what this path can write\n"
                "    --if-exists skip     leave it as it is")
        body = {"row_version": existing["row_version"], "display_name": name,
                "owning_node_id": owner}
        updated = api.patch(f"/api/v1/assets/{existing['id']}", body)
        print(f"\n  {name} already existed as {existing['id']} — updated in place.")
        print("  NOTE: exposure and criticality are NOT writable on this path, so an --exposure or")
        print("  --criticality you passed was NOT applied. Use 07_update_record.py for those.")
        print_table([updated], COLUMNS)
        return

    body = {
        "type_id": type_id(api, type_code),
        "display_name": name,
        "owning_node_id": owner,
        # INHERITED means "take the owning node's tier". Sending a tier id here instead makes the
        # asset stop following its organization the next time that changes, which is a decision
        # rather than a default — so the default is to inherit.
        "criticality_mode": "INHERITED" if not criticality else "ASSIGNED",
    }
    if criticality:
        body["criticality_tier_id"] = criticality
    if exposure:
        body["exposure_declared"] = exposure
    created = api.post("/api/v1/assets", body)
    print(f"\n  created {created['id']}")
    print(f"  lifecycle_state = {created['lifecycle_state']}")
    print("\n  Note the lifecycle state. An asset created through the API arrives DISCOVERED, not"
          "\n  ACTIVE: something recorded it, nobody has confirmed it is real and owned. Promote it"
          "\n  in the interface, or with 01_app_inventory.py amend --lifecycle ACTIVE.")
    print_table([created], COLUMNS)


def amend_asset(api: Aspm, asset_id: str, name: str | None, lifecycle: str | None) -> None:
    current = api.get(f"/api/v1/assets/{asset_id}")
    body = {"row_version": current["row_version"]}
    if name:
        body["display_name"] = name
    if lifecycle:
        body["lifecycle_state"] = lifecycle
    if len(body) == 1:
        raise SystemExit("nothing to change: pass --name or --lifecycle")
    # row_version is not optional and not decoration. The server refuses a write carrying a stale
    # version rather than overwriting somebody else's edit, so read-then-write is the contract.
    updated = api.patch(f"/api/v1/assets/{asset_id}", body)
    print(f"\n  row_version {current['row_version']} -> {updated['row_version']}")
    print_table([updated], COLUMNS)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    listing = sub.add_parser("list", help="list the inventory")
    listing.add_argument("--type", help="filter by asset type code, e.g. APPLICATION")
    listing.add_argument("--limit", type=int, default=100, help="page size (default 100)")

    creating = sub.add_parser("create", help="create one asset")
    creating.add_argument("name")
    creating.add_argument("--type", required=True, help="asset type code, e.g. APPLICATION")
    creating.add_argument("--owner", required=True, help="owning org node id")
    creating.add_argument("--exposure", help="INTERNET_PUBLIC | PARTNER_B2B | INTERNAL_ONLY | AIR_GAPPED")
    creating.add_argument("--criticality", help="criticality tier id; omit to inherit from the owner")
    creating.add_argument("--if-exists", choices=["fail", "update", "skip"], default="fail",
                          help="what to do when the name is already taken (default: fail)")

    amending = sub.add_parser("amend", help="rename one asset, or move its lifecycle state")
    amending.add_argument("id")
    amending.add_argument("--name")
    amending.add_argument("--lifecycle", help="e.g. ACTIVE, RETIRED")

    args = parser.parse_args()
    api = Aspm.from_environment()
    try:
        if args.command == "list":
            list_assets(api, args.type, args.limit)
        elif args.command == "create":
            create_asset(api, args.name, args.type, args.owner, args.exposure, args.criticality,
                         args.if_exists)
        else:
            amend_asset(api, args.id, args.name, args.lifecycle)
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        if failure.status == 500 and args.command == "create":
            print("\n  A 500 on a create is most often the identity constraint: an asset with this"
                  "\n  name and type already exists. Re-run with --if-exists update or skip.",
                  file=sys.stderr)
        if failure.status == 404 and args.command != "list":
            print("\n  A 404 on a write is also what insufficient permission looks like: the"
                  "\n  platform does not distinguish 'no such object' from 'not yours'. Check that"
                  "\n  the credential's principal holds ast.asset.create / ast.asset.update through"
                  "\n  a role, because the credential's own list is intersected with that.",
                  file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
