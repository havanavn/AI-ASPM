package aspm.app.assessment;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Images pasted inline into a write-up or a comment.
 *
 * <h2>The type comes from the bytes, never from the upload</h2>
 *
 * <p>A browser sends a {@code Content-Type} and a filename with every upload and both are attacker
 * controlled. If the platform stored what it was told and served it back, an HTML document uploaded
 * as {@code image/png} would be served as HTML <b>from this origin</b> — which is a stored cross-site
 * scripting vulnerability delivered by the attachment feature, in the product that exists to find
 * them.
 *
 * <p>So {@link #sniff} reads the leading bytes and recognises exactly four raster signatures. Anything
 * else is refused. There is no allowlist of declared types to get wrong, because the declaration is
 * never consulted.
 *
 * <p><b>SVG is excluded deliberately</b>, and it is the exclusion people argue about. SVG is a
 * document format that executes script and can reference external resources; an {@code <img>} tag is
 * not a sandbox for one, and an SVG served from this origin and opened directly is a page on the
 * platform's domain written by whoever uploaded it.
 */
public final class AttachmentService {

    /** One megabyte, matching {@code ck_prose_attachment__size} and the transport's body bound. */
    public static final int MAX_BYTES = 1_048_576;

    /** What an inline image may be. Raster only — see the class note on SVG. */
    private static final java.util.Map<String, byte[]> SIGNATURES = java.util.Map.of(
            "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/gif", new byte[] {'G', 'I', 'F', '8'});

    /**
     * One image as stored.
     *
     * <p>A class rather than a record for the same reason as {@code InterfaceResource.Binary}: a
     * record's {@code equals} would compare the bytes by reference.
     */
    public static final class Attachment {

        private final UUID id;
        private final String mediaType;
        private final byte[] content;
        private final String hashHex;

        Attachment(UUID id, String mediaType, byte[] content, String hashHex) {
            this.id = id;
            this.mediaType = mediaType;
            this.content = content == null ? new byte[0] : content.clone();
            this.hashHex = hashHex;
        }

        public UUID id() {
            return id;
        }

        public String mediaType() {
            return mediaType;
        }

        public byte[] content() {
            return content.clone();
        }

        public String hashHex() {
            return hashHex;
        }
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public AttachmentService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * The media type these bytes actually are, or empty if they are not a permitted image.
     *
     * <p>WebP is checked separately because its signature is split: {@code RIFF} at offset 0 and
     * {@code WEBP} at offset 8, with the file length in between. A prefix test alone would accept any
     * RIFF container — a WAV, an AVI — and serve it as an image.
     */
    static Optional<String> sniff(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        for (var entry : SIGNATURES.entrySet()) {
            byte[] signature = entry.getValue();
            boolean matches = true;
            for (int i = 0; i < signature.length; i++) {
                if (bytes[i] != signature[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return Optional.of(entry.getKey());
            }
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /**
     * Stores an image against a subject.
     *
     * <p>Deduplicated by content hash within the subject: pasting the same screenshot twice into one
     * write-up is one image, and the {@code ON CONFLICT} returns the existing identifier rather than
     * failing — a second paste is not an error.
     *
     * @return the identifier, or empty if the bytes are not a permitted image or exceed the cap
     */
    public Optional<UUID> store(Principal principal, String subjectKind, UUID subjectId,
            byte[] content, String originalFilename) throws SQLException {
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            return Optional.empty();
        }
        Optional<String> mediaType = sniff(content);
        if (mediaType.isEmpty()) {
            return Optional.empty();
        }
        byte[] hash = sha256(content);

        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO prose_attachment (tenant_id, subject_kind, subject_id, "
                                + "media_type, byte_size, content_hash, content, original_filename, "
                                + "uploaded_by) "
                                + "VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?) "
                                // DO NOTHING, not DO UPDATE. The obvious form of this — touching a
                                // column to force a RETURNING row — requires the UPDATE privilege,
                                // and the runtime role deliberately has none on this table: stored
                                // bytes are immutable, which is what lets the content hash serve as
                                // an ETag. The second statement below recovers the identifier
                                // instead, so the narrow grant survives the convenience.
                                + "ON CONFLICT (tenant_id, subject_kind, subject_id, content_hash) "
                                + "DO NOTHING "
                                + "RETURNING id")) {
            statement.setString(1, subjectKind);
            statement.setObject(2, subjectId);
            statement.setString(3, mediaType.orElseThrow());
            statement.setInt(4, content.length);
            statement.setBytes(5, hash);
            statement.setBytes(6, content);
            // The filename is recorded and never used to decide anything. It is attacker-controlled
            // text kept for a human reading an audit trail, so it is bounded and never echoed into a
            // header — a filename in Content-Disposition is a response-splitting vector.
            statement.setString(7, originalFilename == null ? null
                    : originalFilename.replaceAll("[\\r\\n\"\\\\]", "")
                            .substring(0, Math.min(200, originalFilename.length())));
            statement.setObject(8, principal == null ? null : principal.principalId());
            Optional<UUID> stored = Optional.empty();
            try (ResultSet keys = statement.executeQuery()) {
                if (keys.next()) {
                    stored = Optional.of(keys.getObject(1, UUID.class));
                }
            }
            if (stored.isPresent()) {
                // Evidence, which DOC-14 names among the things whose handling leaves a trace. The
                // hash and the size, never the bytes: the bytes are expected to be malicious (the
                // fourth surface in CLAUDE.md) and the trail is not a second place to keep them.
                audit.domainChange(connection, principal, "prose_attachment",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, stored.orElseThrow(),
                        null, java.util.Map.of("subject_kind", subjectKind,
                                "subject_id", subjectId.toString(),
                                "media_type", mediaType.orElseThrow(),
                                "byte_size", Integer.valueOf(content.length),
                                "content_sha256", java.util.HexFormat.of().formatHex(hash)));
                connection.commit();
                return stored;
            }
            // No row returned means the same bytes are already attached to this subject. The insert
            // wrote nothing, so the unit of work is discarded explicitly before the lookup that
            // recovers what is already there.
            connection.rollback();

            // Returning the existing identifier makes a second paste a no-op rather than an error,
            // which is what somebody re-pasting a screenshot expects.
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT id FROM prose_attachment WHERE subject_kind = ? AND subject_id = ? "
                            + "AND content_hash = ?")) {
                existing.setString(1, subjectKind);
                existing.setObject(2, subjectId);
                existing.setBytes(3, hash);
                try (ResultSet r = existing.executeQuery()) {
                    return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.empty();
                }
            }
        }
    }

    /**
     * Loads an image for serving.
     *
     * <p>The caller's SCOPE is not applied here and that is deliberate: an attachment is reachable
     * only through the write-up that references it, and the page holding that reference has already
     * been authorized. Applying a second, different scope rule at the byte-serving route would be a
     * second enforcement point that can disagree with the first ({@code CON-PLT-009}). The identifier
     * is a version-7 UUID and is not guessable, and the route requires an authenticated session.
     */
    public Optional<Attachment> load(Principal principal, UUID id) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, media_type, content, encode(content_hash, 'hex') "
                                + "FROM prose_attachment WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet r = statement.executeQuery()) {
                return r.next()
                        ? Optional.of(new Attachment(r.getObject(1, UUID.class), r.getString(2),
                                r.getBytes(3), r.getString(4)))
                        : Optional.empty();
            }
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JDK", e);
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
