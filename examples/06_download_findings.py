#!/usr/bin/env python3
"""
Get the finding list OUT — as JSON, as CSV, or as the spreadsheet the interface produces.

    python3 06_download_findings.py list
    python3 06_download_findings.py list --state OPEN --limit 200
    python3 06_download_findings.py json  --out findings.json       # versioned API, paginated
    python3 06_download_findings.py csv   --out findings.csv        # versioned API, flattened here
    python3 06_download_findings.py xlsx  --out findings.xlsx       # the interface's own export
    python3 06_download_findings.py summary

THREE EXPORTS, AND THEY ARE NOT THE SAME FILE
---------------------------------------------
`json` and `csv` both walk `GET /api/v1/findings` — versioned, keyset-paginated, a fixed column set.
The CSV is flattened by this script, not by the server.

`xlsx` fetches `GET /api/ui/vulnerabilities/export`, which returns a **spreadsheet**, not CSV: the
content type is `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` and the body is a
ZIP container, so it is written as bytes. It carries the columns the vulnerability dashboard shows.
Use it when somebody asks for "the list I can see on that screen"; use `json` or `csv` when something
downstream has to keep working across platform upgrades.

WHAT IS DELIBERATELY NOT IN EITHER
----------------------------------
`finding.description` and `raw_source_record_ref` are absent from the API projection. Finding content
is attacker-authored by design — it is the platform's fifth highest-risk surface, and a code snippet
recovered from a repository can carry an injection payload aimed at whatever reads it next. Serving
it belongs to the evidence-handling path, from a separate origin, not to a JSON string field on the
application origin. So neither file below contains finding text. If your report needs it, that is a
deliberate conversation about evidence handling, not a missing query parameter.

A COUNT WITHOUT COVERAGE IS NOT A POSTURE
-----------------------------------------
`summary` prints counts beside how much of the estate has actually been measured, because those are
different facts and only one of them is reassuring. Zero criticals across an estate nobody scanned is
not zero criticals.
"""

import argparse
import csv
import io
import json as jsonlib
import sys
from collections import Counter
from datetime import datetime, timezone

from aspm_client import Aspm, ApiError, print_table

COLUMNS = ["id", "finding_class", "title", "state", "closure_reason",
           "reported_severity_id", "effective_severity_id", "assignee_id",
           "recurrence_count", "source_tool", "first_detected_at", "last_detected_at"]


def severities(api: Aspm) -> dict:
    """
    Severity ids to names. The scale is tenant data (`CFG-VUL-001`), so the names are the tenant's
    and cannot be a constant in this file — a deployment on a four-level scale and one on five must
    both render correctly.
    """
    try:
        options = api.get("/api/ui/vulnerabilities").get("severityOptions") or []
        return {row["id"]: row.get("name", row["id"]) for row in options if "id" in row}
    except (ApiError, AttributeError, TypeError):
        # Named, not silently blank: an unrated column that is actually a failed lookup would read
        # as "nobody has rated these findings", which is a different and much worse fact.
        print("  severity names unavailable; ids shown instead", file=sys.stderr)
        return {}


def fetch(api: Aspm, state: str | None, limit: int) -> list:
    filters = {}
    if state:
        filters["state"] = state
    return api.all_rows("/api/v1/findings", limit=limit, **filters)


def listing(api: Aspm, state: str | None, limit: int) -> None:
    rows = fetch(api, state, limit)
    names = severities(api)
    for row in rows:
        row["severity"] = names.get(row.get("effective_severity_id"), "—")
    print(f"\n  {len(rows)} finding(s)" + (f" in state {state}" if state else "") + "\n")
    print_table(rows[:60], ["title", "severity", "state", "source_tool",
                            "recurrence_count", "last_detected_at"])
    if len(rows) > 60:
        print(f"\n  … {len(rows) - 60} more. Use csv or json to take the whole set.")
    if not rows:
        print("\n  Nothing came back. Before reading that as a clean estate, check what has been")
        print("  measured — run the summary command.")


def to_json(api: Aspm, state: str | None, limit: int, out: str | None) -> None:
    rows = fetch(api, state, limit)
    document = {
        "exported_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "key_id": api.key_id,
        "filter": {"state": state},
        "count": len(rows),
        "scope_note": "What this credential can see. A scoped credential exports its own subtree.",
        "findings": rows,
    }
    text = jsonlib.dumps(document, indent=2)
    if out:
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print(f"\n  wrote {len(rows)} finding(s) to {out}", file=sys.stderr)
    else:
        print(text)


def to_csv(api: Aspm, state: str | None, limit: int, out: str | None) -> None:
    """CSV over the versioned API. Flattened here, so the columns are the ones documented above."""
    rows = fetch(api, state, limit)
    names = severities(api)
    buffer = io.StringIO()
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    buffer.write(f"# AI-ASPM findings export · {stamp} · rows: {len(rows)} · "
                 f"state: {state or 'all'} · key id: {api.key_id}\n")
    buffer.write("# What THIS CREDENTIAL can see. Finding text is deliberately absent — see the\n"
                 "# module docstring on evidence handling.\n")
    writer = csv.DictWriter(buffer, fieldnames=COLUMNS + ["severity"], extrasaction="ignore")
    writer.writeheader()
    for row in rows:
        row = dict(row)
        row["severity"] = names.get(row.get("effective_severity_id"), "")
        writer.writerow(row)
    if out:
        with open(out, "w", encoding="utf-8", newline="") as handle:
            handle.write(buffer.getvalue())
        print(f"\n  wrote {len(rows)} row(s) to {out}", file=sys.stderr)
    else:
        sys.stdout.write(buffer.getvalue())


def to_xlsx(api: Aspm, out: str) -> None:
    """
    The interface's own export, as bytes.

    A spreadsheet rather than CSV, so there is nothing to decode and nothing to reformat: it is
    written exactly as received. Writing it through a text handle would corrupt the ZIP container.
    """
    payload = api.get("/api/ui/vulnerabilities/export")
    if not isinstance(payload, (bytes, bytearray)):
        raise SystemExit(
            "the export endpoint did not return bytes, so its shape has changed. Nothing was "
            "written — a file whose contents nobody checked is worse than no file.")
    with open(out, "wb") as handle:
        handle.write(payload)
    print(f"\n  wrote {len(payload)} bytes to {out}", file=sys.stderr)
    print("  This is a spreadsheet, not CSV. Open it, or read it with a library that reads xlsx.",
          file=sys.stderr)


def summary(api: Aspm) -> None:
    rows = fetch(api, None, 200)
    names = severities(api)
    by_state = Counter(row.get("state", "?") for row in rows)
    by_severity = Counter(names.get(row.get("effective_severity_id"), "unrated") for row in rows)
    by_tool = Counter(row.get("source_tool") or "unrecorded" for row in rows)
    recurring = sum(1 for row in rows if (row.get("recurrence_count") or 0) > 1)

    print(f"\n  {len(rows)} finding(s) this credential can see\n")
    print("  by state");    [print(f"    {k:<28} {v}") for k, v in by_state.most_common()]
    print("\n  by severity"); [print(f"    {k:<28} {v}") for k, v in by_severity.most_common()]
    print("\n  by source");  [print(f"    {k:<28} {v}") for k, v in by_tool.most_common()]
    print(f"\n  came back after being closed  {recurring}")

    # Coverage, beside the counts, from the platform's own coverage states.
    try:
        coverage = api.all_rows("/api/v1/coverage-states", limit=200)
        never = sum(1 for row in coverage if not row.get("latest_snapshot_at"))
        print(f"\n  dependency coverage: {len(coverage)} asset(s) tracked, {never} have never "
              "submitted a bill of materials")
        if never:
            print("  Those assets contribute no component findings. Their zero is not a clean")
            print("  result — nothing has looked, which is the distinction this platform exists to")
            print("  keep visible.")
    except ApiError as failure:
        print(f"\n  coverage unavailable ({failure.status}): needs sbm.coverage.read.")
        print("  Read the counts above as findings-in-what-was-scanned, not as posture.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    lister = sub.add_parser("list", help="print findings")
    lister.add_argument("--state", help="filter by workflow state, e.g. OPEN")
    lister.add_argument("--limit", type=int, default=100)

    jsoner = sub.add_parser("json", help="export via the versioned API")
    jsoner.add_argument("--state")
    jsoner.add_argument("--limit", type=int, default=200)
    jsoner.add_argument("--out")

    csver = sub.add_parser("csv", help="CSV over the versioned API")
    csver.add_argument("--state")
    csver.add_argument("--limit", type=int, default=200)
    csver.add_argument("--out")

    xlsxer = sub.add_parser("xlsx", help="the interface's own spreadsheet export")
    xlsxer.add_argument("--out", required=True, help="a path: the body is binary")

    sub.add_parser("summary", help="counts beside coverage")

    args = parser.parse_args()
    api = Aspm.from_environment()
    try:
        if args.command == "list":
            listing(api, args.state, args.limit)
        elif args.command == "json":
            to_json(api, args.state, args.limit, args.out)
        elif args.command == "csv":
            to_csv(api, args.state, args.limit, args.out)
        elif args.command == "xlsx":
            to_xlsx(api, args.out)
        else:
            summary(api)
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
