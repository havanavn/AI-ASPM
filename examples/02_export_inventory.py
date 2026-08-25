#!/usr/bin/env python3
"""
Export the whole application inventory to CSV.

    python3 02_export_inventory.py > inventory.csv
    python3 02_export_inventory.py --type APPLICATION --out inventory.csv
    python3 02_export_inventory.py --with-declared-fields --type PROJECT --out projects.csv

TWO THINGS THIS EXPORT STATES THAT A NAIVE ONE WOULD NOT
--------------------------------------------------------
**It follows the cursor to the end, and says so.** The collection is keyset-paginated: each page
carries `next_cursor` and the next request resumes from the last row rather than from an offset. An
offset export over an inventory that is being written shows a row twice or skips one, and an export
that silently skipped rows is worse than no export — somebody will reconcile against it.

**It records what it could see, not what exists.** A credential is scoped, so this file is the
inventory *within that scope*. The header comment carries the row count and the scope so a reader
three months later can tell a small estate from a narrow credential. That distinction is the whole
first principle of this platform: measured-and-empty must be distinguishable from not-measured.

DECLARED FIELDS ARE NOT ON THIS PATH
------------------------------------
`/api/v1/assets` exposes a fixed column set and no `attributes`. With --with-declared-fields the
script fetches each row's declared fields from the interface endpoint instead — one extra call per
asset, so it is off by default and honest about the cost rather than quietly making 500 calls.
"""

import argparse
import csv
import io
import sys
from datetime import datetime, timezone

from aspm_client import Aspm, ApiError

BASE_COLUMNS = [
    "id", "display_name", "type_id", "owning_node_id", "lifecycle_state",
    "exposure_declared", "exposure_observed", "exposure_conflict",
    "criticality_mode", "criticality_tier_id", "tags",
    "first_seen_at", "last_confirmed_at", "row_version",
]


def type_codes(api: Aspm) -> dict:
    return {row["id"]: row["code"] for row in api.all_rows("/api/v1/asset-types")}


def declared_fields_for(api: Aspm, type_code: str) -> list:
    """
    The tenant's declared field keys for one asset type.

    From the interface endpoint, because the versioned API has no discovery for them. That is a real
    gap and this comment is where it is stated rather than worked around silently.
    """
    try:
        catalogue = api.get("/api/ui/settings/fields", type=type_code)
        return [field["key"] for field in catalogue.get("fields", [])]
    except ApiError as failure:
        print(f"  declared fields unavailable ({failure.status}); exporting base columns only",
              file=sys.stderr)
        return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--type", help="asset type code to export, e.g. APPLICATION")
    parser.add_argument("--out", help="write here instead of stdout")
    parser.add_argument("--limit", type=int, default=200, help="page size (default 200)")
    parser.add_argument("--with-declared-fields", action="store_true",
                        help="also fetch each asset's declared fields — one extra call per asset")
    args = parser.parse_args()

    api = Aspm.from_environment()
    try:
        codes = type_codes(api)
        filters = {}
        if args.type:
            matched = [i for i, c in codes.items() if c == args.type]
            if not matched:
                raise SystemExit(f"no asset type '{args.type}'. Declared: "
                                 + ", ".join(sorted(set(codes.values()))))
            filters["type_id"] = matched[0]

        rows, pages = [], 0
        for page in api.pages("/api/v1/assets", limit=args.limit, **filters):
            rows.extend(page)
            pages += 1
            print(f"  page {pages}: {len(page)} row(s), {len(rows)} so far", file=sys.stderr)

        columns = list(BASE_COLUMNS)
        declared = []
        if args.with_declared_fields and args.type:
            declared = declared_fields_for(api, args.type)
            columns += [f"attr.{key}" for key in declared]
            for index, row in enumerate(rows, start=1):
                record = api.get(f"/api/ui/projects/{row['id']}/editor") \
                    if args.type == "PROJECT" else None
                values = (record or {}).get("project", {}).get("attributes", {}) or {}
                for key in declared:
                    value = values.get(key)
                    row[f"attr.{key}"] = ", ".join(map(str, value)) if isinstance(value, list) \
                        else ("" if value is None else value)
                if index % 25 == 0:
                    print(f"  declared fields: {index}/{len(rows)}", file=sys.stderr)
        elif args.with_declared_fields:
            print("  --with-declared-fields needs --type: the field catalogue is per asset type",
                  file=sys.stderr)

        buffer = io.StringIO()
        # The provenance header. An export with no scope and no timestamp becomes "the inventory" in
        # somebody's slide three months later, and nobody can then say what it was a view of.
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        buffer.write(f"# AI-ASPM inventory export · {stamp}\n")
        buffer.write(f"# rows: {len(rows)} · asset type: {args.type or 'all'} · "
                     f"key id: {api.key_id}\n")
        buffer.write("# This is what THIS CREDENTIAL can see. A scoped credential exports its own\n"
                     "# subtree, so a small file may mean a small estate or a narrow scope.\n")
        writer = csv.DictWriter(buffer, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            row = dict(row)
            row["type_id"] = codes.get(row.get("type_id"), row.get("type_id"))
            if isinstance(row.get("tags"), list):
                row["tags"] = ", ".join(row["tags"])
            writer.writerow(row)

        if args.out:
            with open(args.out, "w", encoding="utf-8", newline="") as handle:
                handle.write(buffer.getvalue())
            print(f"\n  wrote {len(rows)} row(s) and {len(columns)} column(s) to {args.out}",
                  file=sys.stderr)
        else:
            sys.stdout.write(buffer.getvalue())
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
