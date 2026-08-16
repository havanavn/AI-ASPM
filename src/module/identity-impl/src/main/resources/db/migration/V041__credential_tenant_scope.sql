-- =============================================================================
-- V041 — a credential may cover the whole tenant, and that has to be expressible.
--
-- V037 made `scope_node_id` NOT NULL on the reasoning that a pinned scope is what makes a class F
-- operation's revalidation meaningful. That reasoning is right and the column was wrong: this estate
-- has SIX root organization nodes, so "everything" is not a node — it cannot be named, and an
-- administrator issuing a key for a central build platform had no way to say what they meant. They
-- would have picked one root and quietly locked the pipeline out of five sixths of the estate.
--
-- NULL now means the whole tenant, matching `alert_webhook.scope_node_id`, which already used that
-- convention for the same reason. It is not a weaker control:
--
--   * the resolver expands NULL to every org node IN THE TENANT — never across tenants, because the
--     row-level policy is what bounds it and that is unaffected;
--   * `ServiceCredentialAdmin#issue` refuses to write NULL unless the issuing administrator's own
--     scope already reaches every node. Somebody scoped to one division cannot mint a tenant-wide
--     credential, which is the escalation this column's NOT NULL was standing in for;
--   * the interface renders it as "everything you can reach" rather than as a blank, because a null
--     shown as an empty cell reads as unset rather than as deliberate.
-- =============================================================================

ALTER TABLE service_credential ALTER COLUMN scope_node_id DROP NOT NULL;

COMMENT ON COLUMN service_credential.scope_node_id IS
    'The organization this credential is pinned to, or NULL for the whole tenant. NULL is written '
    'only where the issuing administrator already reaches every node — a partially scoped '
    'administrator cannot mint a tenant-wide credential.';
