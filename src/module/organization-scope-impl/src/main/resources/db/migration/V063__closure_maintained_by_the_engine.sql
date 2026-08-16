-- =============================================================================
-- V063 — org_closure is maintained by the engine, so creating a node is possible at all.
--
-- WHAT WAS BROKEN. Nothing wrote org_closure. Not the interface, not `POST /api/v1/org-nodes`, not
-- any Java in the repository — `INSERT INTO org_closure` appeared in seed SQL and nowhere else. The
-- rows present in a running deployment came from the seed and from that alone, so the hierarchy was
-- creatable exactly once, by hand, before the application started.
--
-- The failure was at least loud. V004's `assert_closure_self_reference` is DEFERRABLE and fires at
-- commit, so an inserted node with no closure row aborted the whole transaction:
--
--     ERROR: 1 node(s) lack a depth-zero closure self-reference (INV-ORG-13).
--
-- That constraint did its job. A silent version of this defect would have produced a node that every
-- scope query skips — present in the tree, absent from "the subtree of its parent" — and the assets
-- and findings filed under it would have been invisible to the people accountable for them while
-- appearing, to those people, simply not to exist.
--
-- WHY A TRIGGER RATHER THAN JAVA. Two writers already needed this and a third is foreseeable: the
-- interface's inventory form, the REST resource endpoint, and any future import of an organization
-- chart. A closure maintained in application code is maintained once per writer and forgotten on the
-- next one, and the failure mode is not an exception — it is a subtree that quietly scopes to nothing.
-- PP-10: one name, one meaning, ONE PLACE. The engine is the only place every writer passes through.
--
-- WHAT IS NOT HERE. Re-parenting. `org_node.parent_id` is absent from the resource group's updatable
-- set and no interface path moves a node, so the transitive rebuild a move requires would be code
-- with no caller — and a closure rebuild written now and first exercised in two years is a closure
-- rebuild that has never been tested. A BEFORE UPDATE guard refuses the move instead, loudly, which
-- is the honest state of the capability (PP-9). Lifting it is a migration with a rebuild and a test.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Where the hierarchy version comes from.
--
-- NOT from `tenant.hierarchy_version`. The application role has no privilege on `tenant` at all —
-- not even SELECT — and that is deliberate: residency region, entitlement tier and lifecycle state
-- are the migration credential's business, not the runtime's (OPS-DEP-009). The first create through
-- the API failed with "permission denied for table tenant", raised inside this trigger, which is the
-- separation working rather than an oversight.
--
-- The version is taken from `org_closure` instead, which is what `IntakeService` and `SbomIngestion`
-- already do when they stamp a scope descriptor. That is also the more honest source: the version
-- describes the shape of the TREE, and the closure is the tree.
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 1. Insert: the self-reference, plus one row per ancestor of the new node's parent.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION maintain_closure_on_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    version bigint;
BEGIN
    -- The hierarchy version advances with the shape of the tree. Embedded scope descriptors record it
    -- (DOC-04 section 6.6) so that a historical authorization decision can be evaluated against the
    -- hierarchy as it stood; a closure row stamped with a stale version would place the new node in a
    -- past the tree never had.
    SELECT coalesce(max(c.hierarchy_version), 0) + 1 INTO version
      FROM org_closure c
     WHERE c.tenant_id = NEW.tenant_id;

    -- ON CONFLICT DO NOTHING, because the seed inserts nodes and their closure rows together and must
    -- keep working on a fresh database. A conflict here means the row this trigger would write is
    -- already exactly the row that exists: the primary key is (tenant, ancestor, descendant), and the
    -- remaining columns are derived from those three.
    INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
    VALUES (NEW.tenant_id, NEW.id, NEW.id, 0, version)
    ON CONFLICT DO NOTHING;

    IF NEW.parent_id IS NOT NULL THEN
        INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
        SELECT NEW.tenant_id, c.ancestor_id, NEW.id, c.depth + 1, version
          FROM org_closure c
         WHERE c.tenant_id = NEW.tenant_id
           AND c.descendant_id = NEW.parent_id
        ON CONFLICT DO NOTHING;

        -- A parent with no closure rows of its own is a parent that no scope query can reach, and a
        -- child hung beneath it inherits that invisibility. It cannot happen while this trigger is the
        -- only writer; it can happen to data that predates the trigger, which is exactly when nobody
        -- is looking.
        IF NOT EXISTS (SELECT 1 FROM org_closure c
                        WHERE c.tenant_id = NEW.tenant_id
                          AND c.descendant_id = NEW.id
                          AND c.depth > 0) THEN
            RAISE EXCEPTION
                'parent % has no closure rows, so % would be unreachable from every ancestor',
                NEW.parent_id, NEW.id USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS tr_org_node__maintain_closure ON org_node;
CREATE TRIGGER tr_org_node__maintain_closure
    AFTER INSERT ON org_node
    FOR EACH ROW EXECUTE FUNCTION maintain_closure_on_insert();

-- -----------------------------------------------------------------------------
-- 2. Update: refuse a move rather than let the closure describe a tree that no longer exists.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION reject_unsupported_reparent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_id IS DISTINCT FROM OLD.parent_id THEN
        RAISE EXCEPTION
            'moving a node between parents is not supported: org_closure carries the transitive '
            'ancestry of every descendant and nothing rebuilds it, so the subtree would keep '
            'answering scope questions with its former position (INV-ORG-13).'
            USING ERRCODE = 'feature_not_supported';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_org_node__no_reparent ON org_node;
CREATE TRIGGER tr_org_node__no_reparent
    BEFORE UPDATE ON org_node
    FOR EACH ROW EXECUTE FUNCTION reject_unsupported_reparent();
