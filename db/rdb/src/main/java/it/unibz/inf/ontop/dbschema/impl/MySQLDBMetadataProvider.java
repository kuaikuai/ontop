package it.unibz.inf.ontop.dbschema.impl;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import it.unibz.inf.ontop.dbschema.RelationID;
import it.unibz.inf.ontop.exception.MetadataExtractionException;
import it.unibz.inf.ontop.injection.CoreSingletons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySQLDBMetadataProvider extends DefaultSchemaDBMetadataProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLDBMetadataProvider.class);

    // True when ontop.mysql.doris=true: Doris JDBC returns extra catalog/schema
    // in getIndexInfo vs getColumns, requiring table-only ID comparison.
    private final boolean isDoris;

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
        try {
            LOGGER.info("MySQL metadata provider: isDoris={}, catalog={}, schema={}",
                    isDoris, connection.getCatalog(), safeSchema(connection));
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
