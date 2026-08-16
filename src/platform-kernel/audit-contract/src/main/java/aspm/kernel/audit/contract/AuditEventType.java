package aspm.kernel.audit.contract;

/**
 * The auditable action catalogue of DOC-14 section 3, as a machine-readable artifact.
 *
 * <p>{@code SEC-AUD-006}: "The catalogue MUST be maintained as a machine-readable artifact, and every
 * audit-emitting code path MUST reference a catalogued type. An uncatalogued type MUST fail the
 * build." An enum is the strongest available reading of that last sentence — an uncatalogued type is
 * not a lint finding but a symbol that does not resolve, so the build fails for the same reason a
 * typo does. A string constant with a registry check would move the failure to runtime, and DOC-14
 * states the reason coverage must be defined: gaps are otherwise "discovered during an audit rather
 * than before one".
 *
 * <p>Per-aggregate domain state changes are deliberately not enumerated here; see
 * {@link DomainChangeKind}. DOC-14 section 3 defers them to "the machine-readable catalogue"
 * enumerated per aggregate, and no aggregate exists until prompt 4.
 */
public enum AuditEventType {

    // ---- Authentication ----
    AUTH_SUCCEEDED("auth.succeeded"),
    AUTH_FAILED("auth.failed"),
    AUTH_STEP_UP_SUCCEEDED("auth.step_up.succeeded"),
    AUTH_STEP_UP_FAILED("auth.step_up.failed"),
    AUTH_THROTTLED("auth.throttled"),
    SESSION_CREATED("session.created"),
    SESSION_REVOKED("session.revoked"),
    SESSION_EXPIRED("session.expired"),

    // ---- Authorization ----
    AUTHZ_DENIED("authz.denied"),
    ROLE_CREATED("role.created"),
    ROLE_UPDATED("role.updated"),
    ROLE_PERMISSION_CHANGED("role.permission.changed"),
    ASSIGNMENT_GRANTED("assignment.granted"),
    ASSIGNMENT_REVOKED("assignment.revoked"),
    OBJECT_GRANT_ISSUED("object_grant.issued"),
    OBJECT_GRANT_REVOKED("object_grant.revoked"),
    OBJECT_GRANT_EXPIRED("object_grant.expired"),
    DELEGATION_CREATED("delegation.created"),
    DELEGATION_EXPIRED("delegation.expired"),
    SOD_CONSTRAINT_CHANGED("sod_constraint.changed"),
    SOD_CONSTRAINT_RELAXED("sod_constraint.relaxed"),

    // ---- Restricted data access (SEC-AUD-007: the read IS the sensitive event) ----
    CREDENTIAL_REVEALED("credential.revealed"),
    SECRET_REVEALED("secret.revealed"),
    EVIDENCE_RETRIEVED("evidence.retrieved"),
    WORKLOAD_MEMBER_DATA_ACCESSED("workload.member_data.accessed"),
    AUDIT_READ("audit.read"),

    // ---- Configuration (DOC-26 T9: the least-visible escalation path) ----
    ORG_NODE_TYPE_CHANGED("org_node_type.changed"),
    WORKFLOW_CHANGED("workflow.changed"),
    WORKFLOW_ACTIVATED("workflow.activated"),
    AUTOMATION_RULE_CHANGED("automation_rule.changed"),
    SCORING_MODEL_CHANGED("scoring_model.changed"),
    SCORING_MODEL_ACTIVATED("scoring_model.activated"),
    SLA_POLICY_CHANGED("sla_policy.changed"),
    TAXONOMY_CHANGED("taxonomy.changed"),
    ATTRIBUTE_SCHEMA_CHANGED("attribute_schema.changed"),
    AI_CONFIGURATION_CHANGED("ai_configuration.changed"),
    CONNECTOR_CONFIGURED("connector.configured"),
    CONNECTOR_CREDENTIAL_ROTATED("connector.credential.rotated"),
    ENTITLEMENT_CHANGED("entitlement.changed"),
    RETENTION_CHANGED("retention.changed"),

    // ---- Bulk and export (SEC-AUD-009: per item AND a summary) ----
    BULK_EXECUTED("bulk.executed"),
    EXPORT_GENERATED("export.generated"),
    REPORT_GENERATED("report.generated"),
    CONFIGURATION_EXPORTED("configuration.exported"),
    CONFIGURATION_IMPORTED("configuration.imported"),
    TENANT_DATA_EXPORTED("tenant_data.exported"),
    ACCESS_REVIEW_EXPORTED("access_review.exported"),

    // ---- Ingestion ----
    IMPORT_STARTED("import.started"),
    IMPORT_COMPLETED("import.completed"),
    IMPORT_REVERSED("import.reversed"),
    SBOM_SUBMITTED("sbom.submitted"),
    SBOM_REJECTED("sbom.rejected"),
    MATCH_RUN_COMPLETED("match_run.completed"),
    RECORD_QUARANTINED("record.quarantined"),
    MIGRATION_EXECUTED("migration.executed"),

    // ---- AI (ADR-005: the ledger and its promotion are both audited) ----
    AI_INVOKED("ai.invoked"),
    AI_SUGGESTION_GENERATED("ai.suggestion.generated"),
    AI_SUGGESTION_PROMOTED("ai.suggestion.promoted"),
    AI_SUGGESTION_DISMISSED("ai.suggestion.dismissed"),
    AI_REDACTION_APPLIED("ai.redaction.applied"),

    // ---- Privileged and platform ----
    BREAK_GLASS_REQUESTED("break_glass.requested"),
    BREAK_GLASS_APPROVED("break_glass.approved"),
    BREAK_GLASS_ACTIVATED("break_glass.activated"),
    BREAK_GLASS_EXPIRED("break_glass.expired"),
    ENFORCEMENT_BYPASS_USED("enforcement_bypass.used"),
    ERASURE_EXECUTED("erasure.executed"),
    LEGAL_HOLD_APPLIED("legal_hold.applied"),
    LEGAL_HOLD_RELEASED("legal_hold.released"),
    KEY_ROTATED("key.rotated"),
    KEY_DESTROYED("key.destroyed"),
    MIGRATION_SCHEMA_APPLIED("migration.schema.applied"),
    INTEGRITY_VERIFIED("integrity.verified"),
    INTEGRITY_FAILED("integrity.failed"),

    // ---- Tenant lifecycle ----
    TENANT_PROVISIONED("tenant.provisioned"),
    TENANT_SUSPENDED("tenant.suspended"),
    TENANT_REACTIVATED("tenant.reactivated"),
    TENANT_OFFBOARDING_STARTED("tenant.offboarding.started"),
    TENANT_OFFBOARDED("tenant.offboarded");

    private final String code;

    AuditEventType(String code) {
        this.code = code;
    }

    /**
     * The stable wire code, as it appears in {@code audit_event.event_type}.
     *
     * <p>Immutable once issued. It appears in exported trails and in an auditor's saved queries, so a
     * changed code silently breaks them — the same reasoning DOC-04 section 8.1 gives for taxonomy
     * codes being immutable while labels are not.
     */
    public String code() {
        return code;
    }
}
