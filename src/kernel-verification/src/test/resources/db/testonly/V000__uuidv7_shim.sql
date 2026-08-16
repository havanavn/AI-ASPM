-- =============================================================================
-- TEST-ONLY SHIM. Never applied to a real deployment.
--
-- ADR-049 sets PostgreSQL 18 as the floor specifically because uuidv7() is native from 18, and the
-- migrations use it for time-ordered primary keys (ADR-031). The embedded binaries available as a Maven
-- artifact reach 17.5.0, so this defines uuidv7() where the server does not.
--
-- *** WHAT THIS MEANS FOR COVERAGE, STATED PLAINLY. ***
-- Everything else the verification suite asserts is PostgreSQL 13-or-earlier behaviour: row-level
-- security with FORCE, USING and WITH CHECK policies, BYPASSRLS role attributes, constraint triggers,
-- deferred triggers, declarative hash and range partitioning, partial and GIN indexes, array
-- containment, and column-level grants. Those are verified for real.
--
-- The ONE thing this shim leaves unverified is native uuidv7() itself — its monotonicity and its
-- time-ordering. ADR-049 records that DOC-04 section 22.4 accepts application-side generation as an
-- alternative, so the platform does not depend on the native function's semantics; it depends only on
-- getting a v7-shaped UUID. This shim produces one.
--
-- Recorded here rather than in a commit message because a reader of the verification results needs to
-- know which claim is weaker, and a shim nobody documented is a silently reduced guarantee.
-- =============================================================================

DO $$
BEGIN
    IF current_setting('server_version_num')::int < 180000 THEN
        -- A v7-shaped UUID: 48-bit big-endian millisecond timestamp, version 7, variant 2, random rest.
        -- Time-ordered by construction, which is the property ADR-031 needs.
        EXECUTE $shim$
            CREATE OR REPLACE FUNCTION uuidv7() RETURNS uuid
                LANGUAGE plpgsql VOLATILE
            AS $body$
            DECLARE
                unix_ms bigint := (extract(epoch FROM clock_timestamp()) * 1000)::bigint;
                bytes bytea := gen_random_bytes(16);
            BEGIN
                -- Overwrite the first 48 bits with the timestamp.
                bytes := set_byte(bytes, 0, ((unix_ms >> 40) & 255)::int);
                bytes := set_byte(bytes, 1, ((unix_ms >> 32) & 255)::int);
                bytes := set_byte(bytes, 2, ((unix_ms >> 24) & 255)::int);
                bytes := set_byte(bytes, 3, ((unix_ms >> 16) & 255)::int);
                bytes := set_byte(bytes, 4, ((unix_ms >> 8) & 255)::int);
                bytes := set_byte(bytes, 5, (unix_ms & 255)::int);
                -- Version 7 in the high nibble of byte 6.
                bytes := set_byte(bytes, 6, ((get_byte(bytes, 6) & 15) | 112));
                -- Variant 2 in the top two bits of byte 8.
                bytes := set_byte(bytes, 8, ((get_byte(bytes, 8) & 63) | 128));
                RETURN encode(bytes, 'hex')::uuid;
            END
            $body$;
        $shim$;
        RAISE NOTICE 'uuidv7() shim installed for server below 18. Native uuidv7 semantics are NOT verified.';
    END IF;
END
$$;

-- pgcrypto supplies gen_random_bytes on servers where it is not built in.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
