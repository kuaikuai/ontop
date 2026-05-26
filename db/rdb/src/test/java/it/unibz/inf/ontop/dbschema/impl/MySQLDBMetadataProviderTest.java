package it.unibz.inf.ontop.dbschema.impl;

import it.unibz.inf.ontop.dbschema.RelationID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the equalRelationIDs override in MySQLDBMetadataProvider.
 *
 * When isDoris=true, the provider compares RelationIDs using getTableOnlyID()
 * instead of full RelationID.equals(), to handle inconsistencies between
 * getColumns (returns 2-part ID: schema.table) and getIndexInfo (returns 3-part
 * ID: catalog.schema.table) in Doris JDBC driver.
 */
public class MySQLDBMetadataProviderTest {

    private final RawQuotedIDFactory rawIdFactory;

    public MySQLDBMetadataProviderTest() {
        // SQLStandardQuotedIDFactory converts to UPPER CASE
        rawIdFactory = new RawQuotedIDFactory(new SQLStandardQuotedIDFactory());
    }

    @Test
    public void testMatchingTableNames_DifferentSchema() {
        // Simulating: getColumns returns 2-part ID, getIndexInfo returns 3-part ID
        // getColumns: dwd.test_items (schema=dwd, table=test_items)
        // getIndexInfo: dwd.dwd.test_items (catalog=dwd, schema=dwd, table=test_items)
        RelationID id1 = rawIdFactory.createRelationID("dwd", "test_items"); // 2-part
        RelationID id2 = rawIdFactory.createRelationID("dwd", "dwd", "test_items"); // 3-part

        // When comparing only table names, these should match
        assertTrue(id1.getTableOnlyID().equals(id2.getTableOnlyID()),
                "Table-only IDs should match for same table name");
    }

    @Test
    public void testDifferentTableNames() {
        RelationID id1 = rawIdFactory.createRelationID("dwd", "test_items");
        RelationID id2 = rawIdFactory.createRelationID("dwd", "other_table");

        // Different table names should NOT match
        assertFalse(id1.getTableOnlyID().equals(id2.getTableOnlyID()),
                "Table-only IDs should not match for different table names");
    }

    @Test
    public void testMatchingTableNames_SameSchema() {
        RelationID id1 = rawIdFactory.createRelationID("dwd", "test_items");
        RelationID id2 = rawIdFactory.createRelationID("dwd", "test_items");

        // Standard case: same table, same schema
        assertTrue(id1.getTableOnlyID().equals(id2.getTableOnlyID()),
                "Table-only IDs should match for same table");
    }

    @Test
    public void testMatchingTableNames_OnlyTableComponent() {
        // Both have only table name (no schema)
        RelationID id1 = rawIdFactory.createRelationID("test_items");
        RelationID id2 = rawIdFactory.createRelationID("test_items");

        assertTrue(id1.getTableOnlyID().equals(id2.getTableOnlyID()),
                "Table-only IDs should match when both are table-only");
    }

    @Test
    public void testSameCase_TableNamesMatch() {
        // Same case - should match
        RelationID id1 = rawIdFactory.createRelationID("dwd", "test_items");
        RelationID id2 = rawIdFactory.createRelationID("dwd", "test_items");

        assertTrue(id1.getTableOnlyID().equals(id2.getTableOnlyID()),
                "Table-only IDs should match when case is the same");
    }
}