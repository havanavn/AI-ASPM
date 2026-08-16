package aspm.app.api;

import java.util.Objects;
import java.util.UUID;

/**
 * An idempotency key, <b>tenant-namespaced</b>. Required on every non-idempotent write.
 *
 * <p><b>Why the tenant is part of the key and not merely a column beside it.</b> A key space shared
 * across tenants makes one tenant's key collide with another's, and a collision returns the FIRST
 * tenant's stored response to the second. That is a cross-tenant disclosure produced by a
 * reliability mechanism, arriving through a code path nobody thinks of as a read.
 *
 * <p>It is also an oracle in the other direction: a client able to probe for key collisions learns
 * that another tenant used a given key. DOC-24 names cache key construction as "the recurring failure
 * point in otherwise correctly isolated systems", and an idempotency store is a cache wearing a
 * different name.
 *
 * <p>So {@link #namespaced} takes the tenant and there is no constructor that omits it.
 */
public record IdempotencyKey(UUID tenantId, String clientKey) {

    /** Bound on the client-supplied portion. An unbounded key is an unbounded store row. */
    public static final int MAX_CLIENT_KEY_LENGTH = 128;

    public IdempotencyKey {
        Objects.requireNonNull(tenantId,
                "the tenant is part of the key (DOC-24). A shared key space returns one tenant's stored "
                        + "response to another — a cross-tenant disclosure produced by a reliability "
                        + "mechanism, through a code path nobody thinks of as a read.");
        Objects.requireNonNull(clientKey, "a client key is required");
        if (clientKey.isBlank()) {
            throw new IllegalArgumentException(
                    "a blank idempotency key provides no idempotency; a retried request would be applied "
                            + "twice while appearing protected");
        }
        if (clientKey.length() > MAX_CLIENT_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "an idempotency key longer than " + MAX_CLIENT_KEY_LENGTH + " characters");
        }
    }

    public static IdempotencyKey namespaced(UUID tenantId, String clientKey) {
        return new IdempotencyKey(tenantId, clientKey);
    }

    /** The storage key. Tenant first, so a prefix scan cannot cross a tenant boundary. */
    public String storageKey() {
        return tenantId + ":" + clientKey;
    }
}
