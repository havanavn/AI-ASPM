#!/usr/bin/env python3
"""
Get findings INTO the platform, from a scanner's SARIF output.

    python3 05_import_findings.py semgrep.sarif --repository group/payments-api
    python3 05_import_findings.py semgrep.sarif --repository group/payments-api \\
                                 --application "Payments API" --project "Card authorization platform"
    python3 05_import_findings.py --demo --repository group/payments-api    # a 2-result document

WHY THERE IS NO "CREATE ONE FINDING" CALL
-----------------------------------------
`/api/v1/findings` is read-only, and that is the design rather than an omission. ADR-011 makes
normalization and deduplication ONE pipeline shared by file import and native matching, so a finding
created through a plain REST POST would bypass fingerprinting — and a finding that bypassed
fingerprinting is a duplicate that nothing ever reconciles. The same weakness reported by two tools,
or by the same tool twice, has to arrive as one finding with a recurrence count, not as two rows.

So findings arrive as a SCAN REPORT and the pipeline decides what is new, what is a recurrence and
what is a duplicate. One parser, SARIF 2.1.0, which semgrep, mobsfscan and CodeQL all emit.

WHAT THIS DOOR DOES NOT ACCEPT
------------------------------
A SARIF result whose location is an `http`/`https` URI — a DAST finding — is held in quarantine with
the reason named rather than ingested. The RUNTIME identity class needs an input a template match
against a URL does not carry, and `PRD-ING-021` forbids substituting a value the source did not
supply. Ingesting it needs a decision recorded as `OQ-028`, not a guess in code. So nuclei output is
accepted, parsed, and its URL-located results are held.

PERMISSION: `ing.findings.import`, and this is a class F operation — SERVICE CREDENTIALS ONLY. A
human session cannot call it however senior the human is, which is what makes the ingestion door
attributable to a pipeline.

IDEMPOTENCY IS REAL HERE AND NOWHERE ELSE. This endpoint implements the check itself: it looks up
the prior import session by key before parsing anything and returns the first submission's report.
Every other write on the platform requires an `Idempotency-Key` and does not act on it — the
dispatcher validates its shape, namespaces it by tenant, and executes the request anyway.

So on THIS door, reuse the key across retries of one logical submission: a retry with a NEW key
ingests again, re-detects every finding, and re-detection of a closed finding REOPENS it, which
manufactures "this keeps coming back" out of a CI timeout.
"""

import argparse
import json
import sys

from aspm_client import Aspm, ApiError

DEMO_SARIF = {
    "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
    "version": "2.1.0",
    "runs": [{
        "tool": {"driver": {"name": "semgrep", "version": "1.86.0", "rules": [
            {"id": "python.lang.security.audit.exec-detected",
             "shortDescription": {"text": "exec() detected"},
             "properties": {"security-severity": "8.1"}},
            {"id": "python.django.security.audit.raw-query",
             "shortDescription": {"text": "Raw SQL query"},
             "properties": {"security-severity": "6.5"}},
        ]}},
        "results": [
            {"ruleId": "python.lang.security.audit.exec-detected",
             "level": "error",
             "message": {"text": "exec() with a non-literal argument reaches user input."},
             "locations": [{"physicalLocation": {
                 "artifactLocation": {"uri": "src/payments/settlement.py"},
                 "region": {"startLine": 142, "startColumn": 9}}}],
             "partialFingerprints": {"primaryLocationLineHash": "a1b2c3d4e5f60718"}},
            {"ruleId": "python.django.security.audit.raw-query",
             "level": "warning",
             "message": {"text": "Raw query built by string concatenation."},
             "locations": [{"physicalLocation": {
                 "artifactLocation": {"uri": "src/payments/reports.py"},
                 "region": {"startLine": 58, "startColumn": 15}}}],
             "partialFingerprints": {"primaryLocationLineHash": "0f1e2d3c4b5a6978"}},
        ],
    }],
}


def report(outcome: dict) -> None:
    """
    Print the outcome by disposition, never as a total.

    `PRD-ING-041` requires the breakdown, and the reason is that "47 findings imported" cannot be
    acted on: forty-seven new weaknesses and forty-seven re-detections of known ones are the same
    number and completely different days.
    """
    print(f"\n  session      {outcome.get('import_session_id')}")
    print(f"  state        {outcome.get('state')}")
    print(f"  target       {outcome.get('target_asset_id')}")
    if outcome.get("target_created_unclaimed"):
        print("               ^ the target did not exist and was created UNCLAIMED. It is in the"
              "\n                 unowned queue until somebody claims it — a real state, not an error.")
    print()
    for label, key in (
            ("results in document", "records_extracted"),
            ("new findings", "ingested"),
            ("already known", "already_known"),
            ("reopened", "reopened"),
            ("merged within document", "merged_within_document"),
            ("held in quarantine", "quarantined"),
            ("severity mapping gaps", "severity_mapping_gaps")):
        value = outcome.get(key)
        if value is not None:
            print(f"  {label:<24} {value}")

    if outcome.get("reopened"):
        print("\n  Something that was CLOSED came back. That is the number worth acting on: a"
              "\n  weakness re-detected after somebody closed it is not a new finding, and the"
              "\n  recurrence count is what stops it being filed twice.")
    if outcome.get("quarantined"):
        print("\n  Held, not dropped. A SARIF result located by an http/https URI is a runtime"
              "\n  finding, and the RUNTIME identity class needs an input a URL does not carry"
              "\n  (OQ-028). Quarantine keeps it retrievable rather than inventing the value.")
    if outcome.get("severity_mapping_gaps"):
        print("\n  A severity the tool reported does not map onto this tenant's scale. The finding"
              "\n  is stored unrated rather than guessed at — a guessed severity is a number"
              "\n  somebody will plan work against.")
    for warning in outcome.get("warnings") or []:
        print(f"  ⚠ {warning}")
    if outcome.get("tools"):
        print(f"\n  tools        {', '.join(outcome['tools'])}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sarif", nargs="?", help="a SARIF 2.1.0 file")
    parser.add_argument("--demo", action="store_true",
                        help="submit a small built-in document instead of a file")
    parser.add_argument("--repository", required=True,
                        help="REQUIRED. The repository the scan ran against — a reference, never a "
                             "clone: the platform stores no source and holds no Git credentials")
    parser.add_argument("--application", help="narrows target resolution")
    parser.add_argument("--project", help="narrows target resolution")
    args = parser.parse_args()

    if args.demo:
        document = DEMO_SARIF
    elif args.sarif:
        with open(args.sarif, encoding="utf-8") as handle:
            document = json.load(handle)
    else:
        raise SystemExit("pass a SARIF file, or --demo")

    if document.get("version") != "2.1.0":
        print(f"  warning: version is {document.get('version')!r}; the registered parser is "
              "SARIF 2.1.0", file=sys.stderr)

    body = {
        "repository": args.repository,
        "document": document,
    }
    if args.application:
        body["application"] = args.application
    if args.project:
        body["project"] = args.project

    api = Aspm.from_environment()
    try:
        outcome = api.post("/api/v1/finding-imports", body)
        report(outcome if isinstance(outcome, dict) else {"raw": outcome})
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        if failure.status == 404:
            print("\n  A 404 here usually means the credential is not a service credential, or its"
                  "\n  principal does not hold ing.findings.import. This is a class F operation:"
                  "\n  human sessions cannot call it at all.", file=sys.stderr)
        if failure.status == 422:
            print("\n  The target could not be resolved. --repository must name a repository the"
                  "\n  platform already knows, or be accompanied by --application / --project.",
                  file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
