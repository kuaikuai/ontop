package it.unibz.inf.ontop.dbschema.impl;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import it.unibz.inf.ontop.dbschema.AttributeNotFoundException;
import it.unibz.inf.ontop.dbschema.MetadataLookup;
import it.unibz.inf.ontop.dbschema.NamedRelationDefinition;
import it.unibz.inf.ontop.dbschema.RelationID;
import it.unibz.inf.ontop.dbschema.UniqueConstraint;
import it.unibz.inf.ontop.exception.MetadataExtractionException;
import it.unibz.inf.ontop.injection.CoreSingletons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySQLDBMetadataProvider extends DefaultSchemaDBMetadataProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLDBMetadataProvider.class);

    // Doris value of information_schema.table_options.TABLE_MODEL for UNIQUE-key tables,
    // which guarantee at-most-one-row-per-key at the model level
    private static final String DORIS_UNIQUE_MODEL = "UNI";

    // True when ontop.mysql.doris=true: Doris JDBC returns extra catalog/schema
    // in getIndexInfo vs getColumns, requiring table-only ID comparison.
    private final boolean isDoris;

    // True when ontop.mysql.doris.uniqueModelKeyConstraints=true: declares the key columns
    // of Doris UNIQUE-model tables as unique constraints (skipped for other models such as
    // DUPLICATE, whose key columns do NOT guarantee uniqueness).
    private final boolean isDorisUniqueModelKeyConstraints;

    @AssistedInject
    MySQLDBMetadataProvider(@Assisted Connection connection, CoreSingletons coreSingletons) throws MetadataExtractionException {
        super(connection,
                metadata -> metadata.storesMixedCaseIdentifiers()
                    ? new MySQLCaseSensitiveTableNamesQuotedIDFactory()
                    : new MySQLCaseNotSensitiveTableNamesQuotedIDFactory(),
                coreSingletons,
                c -> new String[] { c.getCatalog(), "DUMMY" });
        //        "SELECT DATABASE() AS TABLE_SCHEM");
        // https://dev.mysql.com/doc/refman/5.7/en/information-functions.html#function_schema
        this.isDoris = coreSingletons.getDatabaseInfoSupplier().isDoris();
        this.isDorisUniqueModelKeyConstraints = coreSingletons.getDatabaseInfoSupplier().isDorisUniqueModelKeyConstraints();
        try {
            LOGGER.info("MySQL metadata provider: isDoris={}, uniqueModelKeyConstraints={}, catalog={}, schema={}",
                    isDoris, isDorisUniqueModelKeyConstraints, connection.getCatalog(), safeSchema(connection));
        } catch (SQLException | RuntimeException e) {
            LOGGER.info("MySQL metadata provider: isDoris={} (catalog/schema unavailable: {})",
                    isDoris, e.getMessage());
        }
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | RuntimeException e) {
            return "<unsupported>";
        }
    }

    @Override
    protected boolean equalRelationIDs(RelationID extractedId, RelationID givenId) {
        // Doris JDBC returns catalog + schema in getIndexInfo (e.g. dwd.dwd.test_items)
        // while getColumns returns only schema + table (e.g. dwd.test_items).
        // Compare only table names to bypass this inconsistency.
        if (isDoris) {
            String extractedTable = tableKey(extractedId);
            String givenTable = tableKey(givenId);
            boolean equal = extractedTable.equals(givenTable);
            LOGGER.debug("Doris equalRelationIDs: extracted={} given={} tables={} / {} tableOnlyEqual={}",
                    extractedId, givenId, extractedTable, givenTable, equal);
            return equal;
        }
        LOGGER.debug("Doris equalRelationIDs (strict path): extracted={} given={} isDoris={}",
                extractedId, givenId, isDoris);
        return super.equalRelationIDs(extractedId, givenId);
    }

    @Override
    public void insertIntegrityConstraints(NamedRelationDefinition relation, MetadataLookup metadataLookup)
            throws MetadataExtractionException {
        super.insertIntegrityConstraints(relation, metadataLookup);
        if (isDoris && isDorisUniqueModelKeyConstraints)
            insertDorisUniqueModelKeyConstraint(relation);
    }

    /**
     * Declares the key columns of a Doris UNIQUE-model table as a unique constraint.
     *
     * Doris does not expose key information through the JDBC primary-key/index APIs, and its
     * DUPLICATE model does not guarantee key uniqueness. However, the UNIQUE model ("UNI" in
     * information_schema.table_options) guarantees at-most-one-row-per-key at the model level,
     * so its TABLE_MODEL_KEY can safely be declared as a unique constraint. This enables
     * left-join merging for mappings over such tables.
     */
    private void insertDorisUniqueModelKeyConstraint(NamedRelationDefinition relation) throws MetadataExtractionException {
        try {
            RelationID id = getCanonicalRelationId(relation.getID());
            // WORKAROUND: for MySQL, the catalog component carries the schema
            String schema = getRelationCatalog(id);
            String table = getRelationName(id);

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT TABLE_MODEL, TABLE_MODEL_KEY FROM information_schema.table_options " +
                            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next())
                        return;
                    String model = rs.getString("TABLE_MODEL");
                    if (!DORIS_UNIQUE_MODEL.equals(model)) {
                        LOGGER.debug("Doris table {}.{}: model {} does not guarantee key uniqueness, no unique constraint declared",
                                schema, table, model);
                        return;
                    }
                    String keyColumns = rs.getString("TABLE_MODEL_KEY");
                    if (keyColumns == null || keyColumns.isEmpty())
                        return;

                    String name = table + "_DORIS_UNIQUE_MODEL_KEY";
                    UniqueConstraint.Builder builder = UniqueConstraint.builder(relation, name);
                    for (String column : keyColumns.split(","))
                        builder.addDeterminant(rawIdFactory.createAttributeID(column.trim()));
                    builder.build();
                    LOGGER.info("Doris UNIQUE-model table {}.{}: declared {} as unique constraint (model-level key)",
                            schema, table, keyColumns);
                }
            }
        }
        catch (AttributeNotFoundException e) {
            throw new MetadataExtractionException("Doris UNIQUE-model key column not found in " + relation.getID() + ": " + e.getAttributeID());
        }
        catch (SQLException e) {
            throw new MetadataExtractionException(e);
        }
    }


    /**
     * Doris sometimes parses a qualified table name as a single quoted identifier
     * (e.g. `ods.dp_mpo_mopp_material_base_info_dd`) instead of schema + table, while
     * the JDBC metadata returns the bare table name. Compare the last dot-separated
     * segment, ignoring quoting, so both forms match.
     */
    private static String tableKey(RelationID id) {
        String rendered = id.getComponents().get(RelationID.TABLE_INDEX).getSQLRendering();
        String stripped = rendered.replace("`", "").replace("\"", "");
        int lastDot = stripped.lastIndexOf('.');
        return lastDot >= 0 ? stripped.substring(lastDot + 1) : stripped;
    }

    // WORKAROUND for MySQL connector >= 8.0:
    // <https://github.com/ontop/ontop/issues/270>

    @Override
    protected String getRelationCatalog(RelationID relationID) { return super.getRelationSchema(relationID); }

    @Override
    protected String getRelationSchema(RelationID relationID) { return null; }

    @Override
    protected RelationID getRelationID(ResultSet rs, String catalogNameColumn, String schemaNameColumn, String tableNameColumn) throws SQLException {
        return rawIdFactory.createRelationID(rs.getString(catalogNameColumn), rs.getString(tableNameColumn));
    }
}
