-- =============================================================================
-- V023 — images inline in a write-up or a comment.
--
-- WHY NOT `evidence`. That table is for engagement artefacts: it requires an assessment or a finding
-- (ck_evidence__attached), it points at object storage, and it carries a malware verdict that gates
-- availability. A screenshot pasted into a comment on a REQUEST fits none of that — there is no
-- finding yet — and forcing it through would mean inventing a finding to hold a picture.
--
-- WHY THE BYTES ARE HERE AND NOT IN OBJECT STORAGE. Stated as a trade-off rather than a preference.
-- Inline images are small and are read on exactly the page that references them; a hard cap keeps the
-- table bounded. The alternative is a half-wired MinIO path whose failure mode is a broken image and
-- an orphaned row. When the object-store client is wired, `storage_ref` below is where the pointer
-- goes and `content` becomes nullable — the shape does not change.
--
-- WHAT MAKES THIS SAFE TO SERVE BACK. The content type is DERIVED from the bytes, never taken from
-- the upload, and only four raster formats are permitted. SVG is deliberately excluded: it is a
-- document format that executes script, and an <img> tag is not a sandbox for one.
-- =============================================================================

CREATE TABLE IF NOT EXISTS prose_attachment (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid        NOT NULL,
    subject_kind   text        NOT NULL,
    subject_id     uuid        NOT NULL,
    -- The type the SERVER determined by inspecting the leading bytes. There is no column for the type
    -- the client claimed, because storing it invites somebody to serve it.
    media_type     text        NOT NULL,
    byte_size      int         NOT NULL,
    content_hash   bytea       NOT NULL,
    content        bytea,
    storage_ref    text,
    original_filename text,
    uploaded_by    uuid,
    uploaded_at    timestamptz NOT NULL DEFAULT now(),
    retention_until timestamptz,

    CONSTRAINT ck_prose_attachment__subject CHECK
        (subject_kind IN ('FINDING', 'ASSESSMENT_REQUEST', 'ASSET')),
    -- Raster only. An SVG is a document that executes script; serving one from this origin would give
    -- an attacker-authored write-up a script context on the platform's own domain, which is the whole
    -- of a stored cross-site scripting vulnerability.
    CONSTRAINT ck_prose_attachment__media_type CHECK
        (media_type IN ('image/png', 'image/jpeg', 'image/gif', 'image/webp')),
    -- One megabyte. The transport bounds the request body at the same order, and an inline screenshot
    -- that does not fit belongs in evidence with the rest of the engagement material.
    CONSTRAINT ck_prose_attachment__size CHECK (byte_size > 0 AND byte_size <= 1048576),
    CONSTRAINT ck_prose_attachment__stored CHECK
        ((content IS NOT NULL) <> (storage_ref IS NOT NULL))
);

SELECT apply_tenant_isolation('prose_attachment');

-- Deduplication by content within a subject: pasting the same screenshot twice into one write-up is
-- one image. The hash is over the bytes the server stored, not over what was uploaded.
CREATE UNIQUE INDEX IF NOT EXISTS ux_prose_attachment__content
    ON prose_attachment (tenant_id, subject_kind, subject_id, content_hash);

CREATE INDEX IF NOT EXISTS ix_prose_attachment__subject
    ON prose_attachment (tenant_id, subject_kind, subject_id, uploaded_at);
COMMENT ON INDEX ix_prose_attachment__subject IS
    'Serves: listing the images belonging to one write-up, which is how an orphaned upload is found '
    'and how retention is applied.';

COMMENT ON TABLE prose_attachment IS
    'Images inline in a write-up or comment. media_type is derived from the bytes by the server and '
    'never taken from the upload; SVG is excluded because it executes script and an img tag is not a '
    'sandbox.';

GRANT SELECT, INSERT ON prose_attachment TO app_runtime;
GRANT SELECT ON prose_attachment TO integrity_verifier;
-- No UPDATE and no DELETE for the runtime. An image referenced by a write-up that could be swapped
-- afterwards would let the picture in a report change without the report changing.
REVOKE UPDATE, DELETE ON prose_attachment FROM app_runtime;
