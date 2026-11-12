package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HierarchicalPathTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item/data.sql",
                "/parent-bom-item/mapping.obda",
                "/parent-bom-item/ontology.ttl");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void constantStartTraversesOneLevel() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("PATH-SQL:\n" + sql);

        assertTrue("expected the edge join on material_code:\n" + sql,
                sql.toLowerCase().contains("material_code"));
        assertTrue("expected a UNION ALL chain:\n" + sql,
                sql.toUpperCase().contains("UNION ALL"));

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue("expected the parent bom_item to be returned", rs.next());
            assertEquals("1", rs.getString(1));
        }
    }

    @Test
    public void constantStartCarriesNodeAttribute() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?material_code WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :material_code ?material_code .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("PATH-ATTR-SQL:\n" + sql);

        assertTrue("expected material_code carried by the path CTE:\n" + sql,
                sql.toUpperCase().contains("P.\"MATERIAL_CODE\""));

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue("expected the parent bom_item to be returned", rs.next());
            assertEquals("1", rs.getString(1));
            assertEquals("M1", rs.getString(2));
        }
    }
}
