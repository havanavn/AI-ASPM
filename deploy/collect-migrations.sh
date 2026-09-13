#!/bin/sh
# Gathers every migration into deploy/migrate/migrations/ with the path shape apply.sh expects
# (*/src/main/resources/db/migration/V*.sql), so `docker build deploy/migrate` produces an image that
# applies exactly the files the repository holds. Build output is excluded, as apply.sh excludes it.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/migrate/migrations"
rm -rf "$OUT"
mkdir -p "$OUT"
find "$HERE/../src" -path '*/src/main/resources/db/migration/V*.sql' -not -path '*/build/*' | while read -r f; do
    module="$(echo "$f" | sed -E 's#.*/src/(module|platform-kernel)/([^/]+)/src/main/resources/db/migration/.*#\2#')"
    mkdir -p "$OUT/$module/src/main/resources/db/migration"
    cp "$f" "$OUT/$module/src/main/resources/db/migration/"
done
COUNT="$(find "$OUT" -name 'V*.sql' | wc -l | tr -d ' ')"
echo "collected $COUNT migration(s) into $OUT"
