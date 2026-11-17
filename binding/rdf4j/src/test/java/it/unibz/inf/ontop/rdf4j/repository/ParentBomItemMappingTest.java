package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertTrue;

public class ParentBomItemMappingTest extends AbstractRDF4JTest {

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
    public void testSelfJoinUsesParentMaterialCode() {
        String query = "SELECT DISTINCT ?a WHERE { ?a a <http://example.org/ontology#bom_item> ; " +
                "<http://example.org/ontology#parent_bom_item> ?b ; " +
                "<http://example.org/ontology#material_code> ?c }";
        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        System.out.println("PARENT-BOM-SQL:\n" + sql);
        assertTrue("expected the mapping join condition on parent_material_code:\n" + sql,
                sql.contains("parent_material_code"));
    }
}
