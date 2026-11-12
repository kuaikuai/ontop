package it.unibz.inf.ontop.rdf4j.repository;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HierarchicalPathTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item/data.sql",
                "/parent-bom-item/mapping.obda",
                "/parent-bom-item/ontology.ttl");
        PathSpecRegistry registry = getInjector().getInstance(PathSpecRegistry.class);
        registry.upsert(new PathSpec.Self(
                "http://example.org/ontology#parent_bom_item",
                "parent_bom", "material_code", "parent_material_code", "bom_item_id",
                "http://example.org/ontology#bom_item/", Optional.empty()));
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
}
