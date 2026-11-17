package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * :quantity (xsd:integer) and :parent_base_qty (xsd:string) alias the same
 * column BOM_ITEM_ID: each property must keep its own declared datatype when
 * sunk into the path CTE, instead of collapsing to one column-level datatype.
 *
 * :quantity is additionally declared with a conflicting datatype (xsd:string)
 * in a second mapping assertion: the extraction keeps the first declaration
 * (xsd:integer), so numeric filters must keep working.
 *
 * See docs/superpowers/specs/2026-11-17-property-datatype-for-path-sinking-design.md §4.6 and §5.
 */
public class HierarchicalPathMixedDatatypeTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item-mixed/data.sql",
                "/parent-bom-item-mixed/mapping.obda",
                "/parent-bom-item-mixed/ontology.ttl");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void sameColumnPropertiesKeepTheirOwnDatatypes() throws Exception {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?q ?pbq WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :quantity ?q .\n" +
                "  ?ancestor :parent_base_qty ?pbq .\n" +
                "  FILTER(?q = 1)\n" +
                "  FILTER(?pbq = \"1\")\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("MIXED-SQL:\n" + sql);

        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
        assertTrue("the integer-typed property must participate in a numeric comparison:\n" + sql,
                sql.toUpperCase().contains("BIGINT"));
        assertTrue("the string-typed property must participate in a string comparison:\n" + sql,
                sql.toUpperCase().contains("'1'"));

        /*
         * Both variables share the same lexical CTE column, so the native query projects
         * it only once: the value-level check must go through the SPARQL layer, which
         * reconstructs the two literals with their respective datatypes.
         */
        ImmutableList<ImmutableMap<String, String>> rows = executeQuery(query);
        assertEquals(1, rows.size());
        assertEquals("http://example.org/ontology#bom_item/1", rows.get(0).get("ancestor"));
        assertEquals("1", rows.get(0).get("q"));
        assertEquals("1", rows.get(0).get("pbq"));
    }

    @Test
    public void repeatedPropertyPatternSharesTheColumn() throws Exception {
        // the same property used twice with different variables must not duplicate
        // or misalign the CTE column
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?q1 ?q2 WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :quantity ?q1 .\n" +
                "  ?ancestor :quantity ?q2 .\n" +
                "  FILTER(?q1 = 1)\n" +
                "  FILTER(?q2 = 1)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("REPEATED-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
        assertTrue("the shared column must be carried once by the CTE:\n" + sql,
                countOccurrences(sql, "path_col_") == 1);

        ImmutableList<ImmutableMap<String, String>> rows = executeQuery(query);
        assertEquals(1, rows.size());
        assertEquals("http://example.org/ontology#bom_item/1", rows.get(0).get("ancestor"));
        assertEquals("1", rows.get(0).get("q1"));
        assertEquals("1", rows.get(0).get("q2"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + 1))
            count++;
        return count;
    }
}
