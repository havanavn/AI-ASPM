"""
A signed-request client for the AI-ASPM API. Standard library only.

WHY A CLIENT AND NOT `requests` WITH A TOKEN
--------------------------------------------
ADR-004 forbids bearer API keys, so there is no token to put in a header. Every call is signed:

    canonical = method \\n path \\n sha256_hex(body) \\n unix_timestamp \\n nonce
    signature = HMAC-SHA256(sha256(secret), canonical)

    Authorization: ASPM-HMAC-SHA256 key=<key_id>, ts=<unix>, nonce=<hex>, signature=<hex>
    x-aspm-content-sha256: <sha256_hex of the raw body>

Four things are inside the signature and each closes something specific: method and path so a
captured call cannot be replayed against a different operation, the content hash so it cannot be
replayed with different content, the timestamp so a capture expires (300 seconds), and the nonce
which is single-use and enforced by a primary key rather than by a check the server could race on.

THE SIGNING KEY IS NOT THE SECRET. It is `sha256(secret)`. The server stores that derived value, so
a dump of the credential table yields signing keys and not the secrets themselves. Getting this
wrong produces a 401 that says only "authentication required" — every failure is deliberately
indistinguishable, so you cannot tell a bad signature from a revoked key from a replayed nonce.

THE QUERY STRING IS NOT SIGNED. The server builds the canonical path from the request URI's path
component alone. The nonce still makes any replay a one-shot, but treat the query as outside the
integrity envelope: put anything that must be tamper-evident in the body.

WHAT A CREDENTIAL CAN ACTUALLY DO
---------------------------------
Its effective permissions are what it declares INTERSECTED with what its principal holds through
roles. A credential declaring `ast.asset.update` behind a principal whose roles do not grant it
gets **404**, not 403 — the platform does not distinguish "no such object" from "not yours". If a
write returns 404 and you are sure the id exists, check the principal's roles first.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

CLOCK_SKEW_SECONDS = 300


class ApiError(RuntimeError):
    """A non-2xx response, with the platform's own code and message where it sent one."""

    def __init__(self, status: int, body: str, method: str, path: str):
        self.status = status
        self.body = body
        self.method = method
        self.path = path
        try:
            parsed = json.loads(body)
            code = parsed.get("code", "")
            message = parsed.get("message", "")
            field = parsed.get("field")
        except (ValueError, AttributeError):
            code, message, field = "", body[:200], None
        detail = f"{method} {path} -> {status}"
        if code:
            detail += f" {code}"
        if message:
            detail += f": {message}"
        if field:
            detail += f" (field: {field})"
        super().__init__(detail)


class Aspm:
    """
    One credential, one base URL.

    >>> api = Aspm.from_environment()
    >>> api.get("/api/v1/assets", limit=50)["items"]
    """

    def __init__(self, base_url: str, key_id: str, secret: str, timeout: float = 30.0):
        if not key_id or not secret:
            raise ValueError(
                "a key id and secret are required. Mint one in the interface at "
                "Access -> Integrations, or see examples/README.md")
        self.base_url = base_url.rstrip("/")
        self.key_id = key_id
        # The derived value, once, here — not per request, and never the secret itself.
        self.signing_key = hashlib.sha256(secret.encode("utf-8")).digest()
        self.timeout = timeout

    @classmethod
    def from_environment(cls) -> "Aspm":
        """
        Reads ASPM_BASE_URL, ASPM_KEY_ID and ASPM_SECRET.

        The secret comes from the environment rather than a file in this directory because a secret
        in a repository is a secret in every clone of it. In a pipeline it comes from the runner's
        secret store; on a workstation, from a password manager entry you paste per session.
        """
        missing = [name for name in ("ASPM_KEY_ID", "ASPM_SECRET") if not os.environ.get(name)]
        if missing:
            raise SystemExit(
                f"set {' and '.join(missing)} first.\n"
                "  export ASPM_BASE_URL=https://aspm.internal.example   # https, see README\n"
                "  export ASPM_KEY_ID=...\n"
                "  export ASPM_SECRET=...")
        return cls(
            os.environ.get("ASPM_BASE_URL", "http://127.0.0.1:8080"),
            os.environ["ASPM_KEY_ID"],
            os.environ["ASPM_SECRET"])

    # ----------------------------------------------------------------------------- the signed call

    def call(self, method: str, path: str, body=None, query: dict | None = None):
        """
        One signed request. `body` is serialized as JSON; pass None for a GET.

        An `Idempotency-Key` goes on every non-GET, because the platform refuses a scoped write or a
        configuration change without one.

        *** IT IS REQUIRED AND, ON ALMOST EVERY ENDPOINT, NOT HONOURED. AN EARLIER VERSION OF THIS
        COMMENT SAID OTHERWISE AND WAS WRONG. *** The dispatcher validates the key's shape and
        namespaces it by tenant — which is what stops one tenant's replay colliding with another's —
        and then does nothing further with it. There is no stored-outcome table: measured, POSTing
        the same body twice with the SAME key executed twice.

        One endpoint does honour it, and implements the check itself:
        `POST /api/v1/finding-imports` looks up the prior import session by key before parsing
        anything, and returns the first submission's report. That matters there more than anywhere
        else, because a second ingestion re-detects every finding and re-detection of a closed one
        reopens it.

        So treat a retry of any other write as a SECOND EXECUTION. Make the operation safe to repeat
        another way — check whether the record exists first, or use PATCH against a known id.
        """
        raw = "" if body is None else json.dumps(body)
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        timestamp = str(int(time.time()))
        nonce = secrets.token_hex(16)
        # The path WITHOUT the query string: that is what the server signs over.
        canonical = "\n".join([method, path, digest, timestamp, nonce])
        signature = hmac.new(
            self.signing_key, canonical.encode("utf-8"), hashlib.sha256).hexdigest()

        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(
                {k: v for k, v in query.items() if v is not None})

        headers = {
            "Authorization": (f"ASPM-HMAC-SHA256 key={self.key_id}, ts={timestamp}, "
                              f"nonce={nonce}, signature={signature}"),
            "x-aspm-content-sha256": digest,
            "Accept": "application/json",
        }
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method != "GET":
            headers["Idempotency-Key"] = secrets.token_hex(16)

        request = urllib.request.Request(
            url, method=method, data=raw.encode("utf-8") if body is not None else None,
            headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw_response = response.read()
                if not raw_response:
                    return None
                content_type = response.headers.get_content_type()
                if content_type == "application/json":
                    return json.loads(raw_response.decode("utf-8"))
                # Not every export is text. The vulnerability and dependency exports return a
                # spreadsheet — a ZIP container — so decoding everything as UTF-8 fails on the
                # first non-text byte. Bytes are returned as bytes and the caller writes them out.
                if content_type.startswith("text/") or content_type.endswith("+json"):
                    return raw_response.decode("utf-8")
                return raw_response
        except urllib.error.HTTPError as failure:
            raise ApiError(failure.code, failure.read().decode("utf-8", "replace"),
                           method, path) from None

    def get(self, path: str, **query):
        return self.call("GET", path, query=query or None)

    def post(self, path: str, body):
        return self.call("POST", path, body=body)

    def patch(self, path: str, body):
        return self.call("PATCH", path, body=body)

    # ------------------------------------------------------------------------------- pagination

    def pages(self, path: str, limit: int = 100, **query):
        """
        Every page of a v1 collection, following `next_cursor` until it is null.

        Keyset pagination, not offset: the collection is ordered by (sort column, id) and the cursor
        carries the last row's values. An offset page over data that is being written shows a row
        twice or skips one, which for an inventory export means a report that disagrees with itself.
        """
        cursor = None
        while True:
            page = self.call("GET", path, query={**query, "limit": limit, "cursor": cursor})
            yield page.get("items", [])
            cursor = page.get("next_cursor")
            if not cursor:
                return

    def all_rows(self, path: str, limit: int = 100, **query) -> list:
        """Every row of a collection, flattened. Holds the whole collection in memory."""
        return [row for page in self.pages(path, limit=limit, **query) for row in page]


def print_table(rows: list, columns: list[str]) -> None:
    """A plain aligned table. No dependency, and it survives being piped into a file."""
    if not rows:
        print("  (no rows)")
        return
    widths = {c: max(len(c), *(len(str(r.get(c, ""))) for r in rows)) for c in columns}
    print("  " + "  ".join(c.ljust(widths[c]) for c in columns))
    print("  " + "  ".join("-" * widths[c] for c in columns))
    for row in rows:
        print("  " + "  ".join(str(row.get(c, "")).ljust(widths[c]) for c in columns))
