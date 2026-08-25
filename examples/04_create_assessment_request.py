#!/usr/bin/env python3
"""
Raise an assessment request against a project.

    python3 04_create_assessment_request.py triggers
    python3 04_create_assessment_request.py create <project-id> \\
        --title "Pre-go-live test of the payments portal" \\
        --trigger <trigger-id> \\
        --due 2026-10-15 \\
        --env UAT=https://uat-pay.internal.example \\
        --account "Customer:alice@example.test:vault://aspm/uat/alice" \\
        --account "Customer:bob@example.test:vault://aspm/uat/bob" \\
        --detail "Two accounts per role, card data in the UAT copy."
    python3 04_create_assessment_request.py list

THERE IS NO POST ON /api/v1/requests, AND THAT IS DELIBERATE
------------------------------------------------------------
The versioned API can read requests and drive their transitions; it cannot create one. `INV-ASM-07`
is the reason: a submitted request carries a resolved scope descriptor and a draft carries none, so
submission is the act that resolves it — a plain REST POST would produce a request with no resolved
scope, which every later authorization decision would then be made against.

So creation goes through the interface's own endpoint, `POST /api/ui/requests`, which runs the intake
service. It needs `asm.request.submit`. Everything below is that endpoint's body, and being the
interface's contract it carries no versioning promise.

EVERY ROLE NEEDS TWO ACCOUNTS, AND THE SERVER MEANS IT
------------------------------------------------------
One account per role is refused: `ROLE_NEEDS_TWO_ACCOUNTS`. The reason is in the refusal itself —
testing whether one user can reach another user's data needs a second user to reach. That is the
horizontal privilege escalation check, and it is the defect class this product exists to find, so the
intake refuses a request that cannot test for it rather than accepting one that quietly cannot.

TEST CREDENTIALS ARE REFERENCES, NOT PASSWORDS
----------------------------------------------
`--account` takes `role:username:credential_ref`. The reference is a pointer — `vault:…`,
`onepassword:…` — and the schema enforces the shape. The field that takes an actual password exists,
this script does not expose it, and the reason is in the platform's own threat model: it already
concentrates more live credentials than most systems it protects, so a password typed into an intake
form is a credential stored in the highest-value target in the estate. Put it in your vault and pass
the reference.
"""

import argparse
import sys

from aspm_client import Aspm, ApiError, print_table


def triggers(api: Aspm) -> None:
    """
    The tenant's assessment triggers — why a review is happening.

    Tenant data: a tenant that distinguishes "vendor onboarding" or "post-incident review" adds
    rows, and nothing in the platform reads these codes.
    """
    # From the board endpoint, which is where the interface itself reads them.
    body = api.get("/api/ui/board")
    rows = body.get("triggers") or []
    if not rows:
        print("\n  No trigger list came back from /api/ui/board.")
        print("  Read the ids from Configuration, or from assessment_trigger in the database.")
        return
    print(f"\n  {len(rows)} trigger(s)\n")
    print_table(rows, [k for k in ("id", "code", "label", "countsAsFullReview") if k in rows[0]])


def parse_account(raw: str) -> tuple:
    parts = raw.split(":", 2)
    if len(parts) < 3:
        raise SystemExit(
            f"--account expects role:username:credential_ref, got '{raw}'\n"
            "  e.g. --account 'Customer:alice@example.test:vault://aspm/uat/alice'")
    return parts[0], parts[1], parts[2]


def create(api: Aspm, project_id: str, args) -> None:
    environments = []
    for assignment in args.env:
        if "=" not in assignment:
            raise SystemExit(f"--env expects TYPE=url, got '{assignment}'")
        env_type, base_url = assignment.split("=", 1)
        environments.append({
            "envType": env_type.upper(),
            "baseUrl": base_url,
            "vpnRequired": args.vpn,
            # Stated rather than defaulted true: claiming a WAF is in front of the target when none
            # is changes what the assessor plans for, and it is the kind of wrong answer nobody
            # notices until the test finds nothing.
            "protectiveControlPresent": args.protective_control,
            "bypassArranged": bool(args.bypass_method),
            "bypassMethod": args.bypass_method or "",
            "testWindowConstraints": args.window or "",
        })

    roles = {}
    for raw in args.account:
        role_name, username, credential_ref = parse_account(raw)
        roles.setdefault(role_name, []).append({
            "username": username,
            "credentialRef": credential_ref,
            # Deliberately empty. See the module docstring.
            "password": "",
            "mfaEnrolled": args.mfa_enrolled,
            "mfaBypassRef": "",
        })

    body = {
        "title": args.title,
        "projectId": project_id,
        "triggerId": args.trigger,
        "detail": args.detail or "",
        "dueAt": args.due or "",
        "roles": [{"roleName": name, "description": "", "accounts": accounts}
                  for name, accounts in roles.items()],
        "environments": environments,
        "apiCount": args.api_count,
        "gitRepository": args.repository or "",
        "technologyStack": args.stack or "",
        "notes": args.notes or "",
    }

    created = api.post("/api/ui/requests", body)
    print(f"\n  created {created.get('code') or ''} {created.get('id') or created}")
    print("\n  What happens next is a state machine, not a queue: the request enters the workflow")
    print("  your tenant defined, and moving it on is a transition with guards. Drive those with")
    print("  POST /api/v1/requests/{id}/transitions, which IS on the versioned API.")


def listing(api: Aspm, limit: int) -> None:
    rows = api.all_rows("/api/v1/requests", limit=limit)
    print(f"\n  {len(rows)} request(s)\n")
    columns = [c for c in ("request_code", "state", "title", "derived_priority_score", "id")
               if rows and c in rows[0]]
    print_table(rows, columns or ["id"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("triggers", help="the tenant's assessment triggers")

    lister = sub.add_parser("list", help="requests this credential can see")
    lister.add_argument("--limit", type=int, default=100)

    creator = sub.add_parser("create", help="raise a request")
    creator.add_argument("project_id")
    creator.add_argument("--title", required=True)
    creator.add_argument("--trigger", required=True, help="trigger id, see the triggers command")
    creator.add_argument("--due", help="ISO date, e.g. 2026-10-15")
    creator.add_argument("--detail", help="what is being asked for, in a sentence")
    creator.add_argument("--env", action="append", default=[], metavar="TYPE=url",
                         help="repeatable, e.g. --env UAT=https://uat.internal.example")
    creator.add_argument("--account", action="append", default=[],
                         metavar="role:username:credential_ref",
                         help="repeatable — TWO per role minimum, see the module docstring")
    creator.add_argument("--vpn", action="store_true", help="the target needs VPN access")
    creator.add_argument("--mfa-enrolled", action="store_true",
                         help="the test accounts have a second factor enrolled")
    creator.add_argument("--protective-control", action="store_true",
                         help="a WAF or equivalent is in front of the target")
    creator.add_argument("--bypass-method", help="how the protective control is bypassed, if it is")
    creator.add_argument("--window", help="test window constraints")
    creator.add_argument("--api-count", type=int, help="how many API endpoints are in scope")
    creator.add_argument("--repository", help="repository reference — a name, never a clone")
    creator.add_argument("--stack", help="technology stack")
    creator.add_argument("--notes")

    args = parser.parse_args()
    api = Aspm.from_environment()
    try:
        if args.command == "triggers":
            triggers(api)
        elif args.command == "list":
            listing(api, args.limit)
        else:
            create(api, args.project_id, args)
    except ApiError as failure:
        print(f"\n  {failure}", file=sys.stderr)
        if failure.status == 400:
            print("\n  The intake service names the offending field in `field`. It refuses a draft"
                  "\n  rather than storing a half-described request, because an assessment planned"
                  "\n  against a wrong environment is worse than one nobody planned.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
