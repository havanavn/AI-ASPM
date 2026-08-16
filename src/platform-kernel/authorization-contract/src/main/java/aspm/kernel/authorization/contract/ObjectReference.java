package aspm.kernel.authorization.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference to the object an operation names, per DOC-07 section 8.2.
 *
 * <p>Absent for collection operations, which have no single object and are governed by
 * {@code SEC-AUZ-016} instead.
 *
 * <p><b>The identifier's provenance is deliberately not recorded here.</b> {@code SEC-AUZ-017}
 * requires re-validation "independently of how the identifier was obtained, including identifiers
 * returned by a prior response in the same session", and {@code SEC-AUZ-018} states a filtered picker
 * is a usability feature and not a control. A provenance field would invite an evaluator to trust one
 * source over another, which is the defect both requirements exist to prevent.
 */
public record ObjectReference(String kind, UUID id) {

    public ObjectReference {
        Objects.requireNonNull(kind, "object kind is required");
        Objects.requireNonNull(id, "object id is required");
    }
}
