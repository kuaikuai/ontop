package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertTrue;

/**
 * Reproduces the production query shape that failed with
 * "SQLSerializationException: The variable bom_item_id1m26 does not appear in the columnIDs":
 * a subselect-scoped path with the bom_item attribute OPTIONals INSIDE the subselect
 * (n0 = start, n1 = path terminal), a has_component join to material, and the material
 * attribute OPTIONals OUTSIDE the subselect.
 */
public class HierarchicalPathWideQueryTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item-wide/data.sql",
                "/parent-bom-item-wide/mapping.obda",
                "/parent-bom-item-wide/ontology.ttl");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    private static final String PREFIXES =
            "PREFIX : <http://example.org/ontology#>\n" +
            "PREFIX path: <http://ontop.org/path#>\n";

    /**
     * Exact production shape (all bom_item attribute OPTIONals inside the subselect).
     */
    @Test
    public void productionShapeOptionalsInsideSubselect() throws SQLException {
        String query = PREFIXES +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 WHERE {\n" +
                " { SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 WHERE {\n" +
                "?n0 a :bom_item .\n" +
                "?n1 a :bom_item .\n" +
                "?n2 a :material .\n" +
                "?n0 :parent_bom_item+ ?n1 .\n" +
                "?n0 path:maxDepth 2 .\n" +
                "?n1 path:traversalFilter true .\n" +
                "?n1 :has_component ?n2 .\n" +
                "?n0 :bom_item_id ?v0_0 . FILTER(?v0_0 = \"1\")\n" +
                "?n1 :bom_version ?v1_1 . FILTER(?v1_1 = 1)\n" +
                "OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "OPTIONAL { ?n0 :bom_code ?n0_p2 . }\n" +
                "OPTIONAL { ?n0 :unit ?n0_p3 . }\n" +
                "OPTIONAL { ?n0 :process_code ?n0_p4 . }\n" +
                "OPTIONAL { ?n0 :bom_version ?n0_p5 . }\n" +
                "OPTIONAL { ?n0 :quantity ?n0_p6 . }\n" +
                "OPTIONAL { ?n0 :parent_base_qty ?n0_p7 . }\n" +
                "OPTIONAL { ?n0 :parent_material_code ?n0_p8 . }\n" +
                "OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "OPTIONAL { ?n1 :bom_item_id ?n1_p1 . }\n" +
                "OPTIONAL { ?n1 :bom_code ?n1_p2 . }\n" +
                "OPTIONAL { ?n1 :unit ?n1_p3 . }\n" +
                "OPTIONAL { ?n1 :process_code ?n1_p4 . }\n" +
                "OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "OPTIONAL { ?n1 :quantity ?n1_p6 . }\n" +
                "OPTIONAL { ?n1 :parent_base_qty ?n1_p7 . }\n" +
                "OPTIONAL { ?n1 :parent_material_code ?n1_p8 . }\n" +
                " } LIMIT 1000 }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("WIDE-INSIDE-SQL:\n" + sql);
        assertTrue("must not be EMPTY:\n" + sql, !sql.contains("EMPTY"));
    }

    /**
     * Exact production query layout: ALL attribute OPTIONals (n0, n1, material) OUTSIDE the subselect.
     * Production generates one LEFT JOIN per attribute; check what H2 produces for the same shape.
     */
    @Test
    public void productionShapeAllOptionalsOutside() throws SQLException {
        String query = PREFIXES +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 " +
                "?n2_p0 ?n2_p1 ?n2_p2 ?n2_p3 ?n2_p4 ?n2_p5 ?n2_p6 ?n2_p7 ?n2_p8 ?n2_p9 WHERE {\n" +
                " { SELECT DISTINCT ?n0 ?n1 ?n2 WHERE {\n" +
                "?n0 a :bom_item .\n" +
                "?n1 a :bom_item .\n" +
                "?n2 a :material .\n" +
                "?n0 :parent_bom_item+ ?n1 .\n" +
                "?n0 path:maxDepth 2 .\n" +
                "?n1 path:traversalFilter true .\n" +
                "?n1 :has_component ?n2 .\n" +
                "?n0 :bom_item_id ?v0_0 . FILTER(?v0_0 = \"1\")\n" +
                "?n1 :bom_version ?v1_1 . FILTER(?v1_1 = 1)\n" +
                " } LIMIT 1000 }\n" +
                "OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "OPTIONAL { ?n0 :bom_code ?n0_p2 . }\n" +
                "OPTIONAL { ?n0 :unit ?n0_p3 . }\n" +
                "OPTIONAL { ?n0 :process_code ?n0_p4 . }\n" +
                "OPTIONAL { ?n0 :bom_version ?n0_p5 . }\n" +
                "OPTIONAL { ?n0 :quantity ?n0_p6 . }\n" +
                "OPTIONAL { ?n0 :parent_base_qty ?n0_p7 . }\n" +
                "OPTIONAL { ?n0 :parent_material_code ?n0_p8 . }\n" +
                "OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "OPTIONAL { ?n1 :bom_item_id ?n1_p1 . }\n" +
                "OPTIONAL { ?n1 :bom_code ?n1_p2 . }\n" +
                "OPTIONAL { ?n1 :unit ?n1_p3 . }\n" +
                "OPTIONAL { ?n1 :process_code ?n1_p4 . }\n" +
                "OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "OPTIONAL { ?n1 :quantity ?n1_p6 . }\n" +
                "OPTIONAL { ?n1 :parent_base_qty ?n1_p7 . }\n" +
                "OPTIONAL { ?n1 :parent_material_code ?n1_p8 . }\n" +
                "OPTIONAL { ?n2 :material_code ?n2_p0 . }\n" +
                "OPTIONAL { ?n2 :material_name ?n2_p1 . }\n" +
                "OPTIONAL { ?n2 :color ?n2_p2 . }\n" +
                "OPTIONAL { ?n2 :category_code ?n2_p3 . }\n" +
                "OPTIONAL { ?n2 :safety_stock ?n2_p4 . }\n" +
                "OPTIONAL { ?n2 :is_critical ?n2_p5 . }\n" +
                "OPTIONAL { ?n2 :procurement_type ?n2_p6 . }\n" +
                "OPTIONAL { ?n2 :spec_width ?n2_p7 . }\n" +
                "OPTIONAL { ?n2 :spec_length ?n2_p8 . }\n" +
                "OPTIONAL { ?n2 :plant_code ?n2_p9 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("WIDE-ALL-OUTSIDE-SQL:\n" + sql);
        assertTrue("must not be EMPTY:\n" + sql, !sql.contains("EMPTY"));
    }

    /**
     * Same but with the material attribute OPTIONals OUTSIDE the subselect (exact production layout).
     */
    @Test
    public void productionShapeMaterialOptionalsOutside() throws SQLException {
        String query = PREFIXES +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 " +
                "?n2_p0 ?n2_p1 ?n2_p2 ?n2_p3 ?n2_p4 ?n2_p5 ?n2_p6 ?n2_p7 ?n2_p8 ?n2_p9 WHERE {\n" +
                " { SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 WHERE {\n" +
                "?n0 a :bom_item .\n" +
                "?n1 a :bom_item .\n" +
                "?n2 a :material .\n" +
                "?n0 :parent_bom_item+ ?n1 .\n" +
                "?n0 path:maxDepth 2 .\n" +
                "?n1 path:traversalFilter true .\n" +
                "?n1 :has_component ?n2 .\n" +
                "?n0 :bom_item_id ?v0_0 . FILTER(?v0_0 = \"1\")\n" +
                "?n1 :bom_version ?v1_1 . FILTER(?v1_1 = 1)\n" +
                "OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "OPTIONAL { ?n0 :bom_code ?n0_p2 . }\n" +
                "OPTIONAL { ?n0 :unit ?n0_p3 . }\n" +
                "OPTIONAL { ?n0 :process_code ?n0_p4 . }\n" +
                "OPTIONAL { ?n0 :bom_version ?n0_p5 . }\n" +
                "OPTIONAL { ?n0 :quantity ?n0_p6 . }\n" +
                "OPTIONAL { ?n0 :parent_base_qty ?n0_p7 . }\n" +
                "OPTIONAL { ?n0 :parent_material_code ?n0_p8 . }\n" +
                "OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "OPTIONAL { ?n1 :bom_item_id ?n1_p1 . }\n" +
                "OPTIONAL { ?n1 :bom_code ?n1_p2 . }\n" +
                "OPTIONAL { ?n1 :unit ?n1_p3 . }\n" +
                "OPTIONAL { ?n1 :process_code ?n1_p4 . }\n" +
                "OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "OPTIONAL { ?n1 :quantity ?n1_p6 . }\n" +
                "OPTIONAL { ?n1 :parent_base_qty ?n1_p7 . }\n" +
                "OPTIONAL { ?n1 :parent_material_code ?n1_p8 . }\n" +
                " } LIMIT 1000 }\n" +
                "OPTIONAL { ?n2 :material_code ?n2_p0 . }\n" +
                "OPTIONAL { ?n2 :material_name ?n2_p1 . }\n" +
                "OPTIONAL { ?n2 :color ?n2_p2 . }\n" +
                "OPTIONAL { ?n2 :category_code ?n2_p3 . }\n" +
                "OPTIONAL { ?n2 :safety_stock ?n2_p4 . }\n" +
                "OPTIONAL { ?n2 :is_critical ?n2_p5 . }\n" +
                "OPTIONAL { ?n2 :procurement_type ?n2_p6 . }\n" +
                "OPTIONAL { ?n2 :spec_width ?n2_p7 . }\n" +
                "OPTIONAL { ?n2 :spec_length ?n2_p8 . }\n" +
                "OPTIONAL { ?n2 :plant_code ?n2_p9 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("WIDE-OUTSIDE-SQL:\n" + sql);
        assertTrue("must not be EMPTY:\n" + sql, !sql.contains("EMPTY"));
    }
}
