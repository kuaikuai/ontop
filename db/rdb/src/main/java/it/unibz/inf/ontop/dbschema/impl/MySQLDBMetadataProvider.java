package it.unibz.inf.ontop.dbschema.impl;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import it.unibz.inf.ontop.dbschema.RelationID;
import it.unibz.inf.ontop.exception.MetadataExtractionException;
import it.unibz.inf.ontop.injection.CoreSingletons;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MySQLDBMetadataProvider extends DefaultSchemaDBMetadataProvider {

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
    }

    @Override
    protected boolean equalRelationIDs(RelationID extractedId, RelationID givenId) {
        // Doris JDBC returns catalog + schema in getIndexInfo (e.g. dwd.dwd.test_items)
        // while getColumns returns only schema + table (e.g. dwd.test_items).
        // Compare only table names to bypass this inconsistency.
        if (isDoris) {
            return extractedId.getTableOnlyID().equals(givenId.getTableOnlyID());
        }
        return super.equalRelationIDs(extractedId, givenId);
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
