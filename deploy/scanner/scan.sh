#!/bin/sh
# =============================================================================================
# The scheduled re-scan worker.
#
# WHAT IT IS. A dumb ticker. Every SCAN_TICK_SECONDS it asks the platform "is anything due", and
# the platform answers from the schedule an administrator set in the dashboard. The schedule is
# deliberately NOT here: a crontab is invisible to the person with the permission to change it, and
# two replicas of a cron container is two schedules.
#
# WHY IT RUNS TRIVY RATHER THAN THE PLATFORM DOING SO. The platform is a JVM that must not shell out
# to a scanner, and Trivy needs a vulnerability database it updates itself. Keeping them in separate
# containers means the database is Trivy's problem and the estate is the platform's.
#
# WHAT IT NEVER TOUCHES. The object store. The document arrives in the response, so a compromised
# scanner cannot read the archive of every bill of materials the group has ever submitted. It holds
# one credential, scoped to one permission.
#
# ADR-013 and ADR-024 hold: Trivy is pointed at a stored DOCUMENT, never at source. Nothing here
# clones a repository and no Git credential exists to do it with.
# =============================================================================================
set -eu

API="${ASPM_API:-http://app:8080}"
KEY="${ASPM_SCAN_KEY_ID:?set ASPM_SCAN_KEY_ID}"
SECRET="${ASPM_SCAN_SECRET:?set ASPM_SCAN_SECRET}"
TICK="${SCAN_TICK_SECONDS:-900}"

# The signing key is the SHA-256 of the secret, not the secret itself — the platform stores only that
# digest, so both sides derive the HMAC key the same way (ADR-004, ServiceCredentialResolver).
SIGNKEY=$(printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1)

call() { # method path body
  method="$1"; path="$2"; body="${3:-}"
  ts=$(date +%s)
  nonce=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  ch=$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)
  sig=$(printf '%s\n%s\n%s\n%s\n%s' "$method" "$path" "$ch" "$ts" "$nonce" \
        | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$SIGNKEY" -binary \
        | od -An -tx1 | tr -d ' \n')
  if [ "$method" = "GET" ]; then
    curl -sS -X GET "$API$path" \
      -H "x-aspm-content-sha256: $ch" \
      -H "Authorization: ASPM-HMAC-SHA256 key=$KEY, ts=$ts, nonce=$nonce, signature=$sig"
  else
    curl -sS -X "$method" "$API$path" \
      -H "Content-Type: application/json" -H "Idempotency-Key: $nonce" \
      -H "x-aspm-content-sha256: $ch" \
      -H "Authorization: ASPM-HMAC-SHA256 key=$KEY, ts=$ts, nonce=$nonce, signature=$sig" \
      --data-binary "$body"
  fi
}

echo "scan worker: ticking every ${TICK}s against ${API}"

while true; do
  # The database is refreshed before the batch, not per document: Trivy caches it and a hundred
  # scans against the same database is the point of a batch.
  trivy --cache-dir /cache image --download-db-only >/dev/null 2>&1 || \
    echo "scan worker: vulnerability database update failed, scanning with what is cached"
  VERSION=$(trivy --cache-dir /cache version --format json 2>/dev/null \
            | grep -o '"UpdatedAt":"[^"]*"' | head -1 | cut -d'"' -f4)

  PENDING=$(call GET /api/v1/rescans/pending || echo '{"items":[]}')
  COUNT=$(printf '%s' "$PENDING" | jq -r '.items | length' 2>/dev/null || echo 0)
  if [ "$COUNT" -gt 0 ]; then
    echo "scan worker: $COUNT snapshot(s) due, intelligence $VERSION"
    i=0
    while [ "$i" -lt "$COUNT" ]; do
      ID=$(printf '%s' "$PENDING" | jq -r ".items[$i].snapshot_id")
      NAME=$(printf '%s' "$PENDING" | jq -r ".items[$i].artifact")
      printf '%s' "$PENDING" | jq -r ".items[$i].document" > /tmp/sbom.json

      # Scanning a BILL OF MATERIALS, not a repository. This is the whole architectural point.
      if trivy --cache-dir /cache sbom --format json --quiet /tmp/sbom.json > /tmp/out.json 2>/dev/null; then
        RESULT=$(jq -c --arg s "trivy" --arg v "$VERSION" \
                 '{scanner:$s, intelligence_version:$v, results:.}' /tmp/out.json)
        ANSWER=$(call POST "/api/v1/rescans/$ID" "$RESULT" || echo '{}')
        NEW=$(printf '%s' "$ANSWER" | jq -r '.newly_detected // 0' 2>/dev/null || echo 0)
        echo "scan worker: $NAME -> $NEW newly detected"
      else
        echo "scan worker: $NAME -> scanner failed, leaving the previous verdict in place"
      fi
      i=$((i + 1))
    done
  fi
  # Housekeeping, on the same tick. Cheap, idempotent, and safe to run concurrently — a DELETE over a
  # closed predicate converges rather than doubling, which is why it can ride a timer with no leader
  # election while the scan above could not.
  REAPED=$(call POST /api/v1/session-reap '{}' || echo '{}')
  echo "session reap: $REAPED"

  sleep "$TICK"
done
