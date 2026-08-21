-- =============================================================================
-- V067 — INTEGER joins the declared-attribute type set.
--
-- WHY. The declared-attribute catalogue (V019) supports TEXT, LONG_TEXT, SINGLE_SELECT,
-- MULTI_SELECT, URL and BOOLEAN. A tenant tracking "how many API endpoints does this project
-- expose" had only TEXT, and a count stored as text cannot be compared, ranged or ordered — so the
-- one question it exists to answer, "which of our projects expose the most surface", is the one
-- question it cannot answer.
--
-- The set of DATA TYPES is product code, not tenant data. A tenant declares which fields exist and
-- what values they permit (ADR-027); it does not invent new storage kinds, because each kind needs
-- an editor widget, a validator and a filter operator that only the product can supply. Adding one
-- is therefore a migration, and is expected to be rare.
--
-- Values are stored in `asset.attributes` as JSON numbers rather than strings, so the jsonb
-- containment index keeps working and a range predicate is possible later.
-- =============================================================================

ALTER TABLE asset_attribute_definition
    DROP CONSTRAINT IF EXISTS ck_asset_attr_def__type;

ALTER TABLE asset_attribute_definition
    ADD CONSTRAINT ck_asset_attr_def__type CHECK
        (data_type IN ('TEXT', 'LONG_TEXT', 'SINGLE_SELECT', 'MULTI_SELECT', 'URL', 'BOOLEAN',
                       'INTEGER'));

COMMENT ON COLUMN asset_attribute_definition.data_type IS
    'Product-fixed storage kinds. Each needs an editor widget, a server-side validator and a filter '
    'operator, so the set is code and not tenant configuration — unlike attribute_key, label_i18n '
    'and permitted_values, which are tenant data (ADR-027).';
