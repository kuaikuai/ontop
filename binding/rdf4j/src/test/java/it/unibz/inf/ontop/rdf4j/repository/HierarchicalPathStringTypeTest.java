package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HierarchicalPathStringTypeTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item-string/data.sql",
                "/parent-bom-item-string/mapping.obda",
                "/parent-bom-item-string/ontology.ttl");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void requiredAttributesAreMergedBySameSourceMerge() throws SQLException {
        String q = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n1_p0 ?n1_p1 ?n1_p2 ?n2_p0 ?n2_p1 WHERE {\n" +
                "  { SELECT DISTINCT ?n0 ?n1 ?n2 ?n0_p0 ?n0_p1 ?n0_p2 ?n1_p0 ?n1_p1 ?n1_p2 ?n2_p0 ?n2_p1 WHERE {\n" +
                "      ?n0 a :bom_item ;\n" +
                "          :parent_bom_item+ ?n1 ;\n" +
                "          :bom_item_id ?v0 ;\n" +
                "          path:maxDepth 2 .\n" +
                "      FILTER(?v0 = \"2\")\n" +
                "      ?n1 :has_component ?n2 ;\n" +
                "          :bom_version ?v1 .\n" +
                "      FILTER(?v1 = \"1\")\n" +
                "      ?n0 :material_code ?n0_p0 ; :bom_code ?n0_p1 ; :unit ?n0_p2 .\n" +
                "      ?n1 :material_code ?n1_p0 ; :bom_code ?n1_p1 ; :unit ?n1_p2 .\n" +
                "      ?n2 :material_code ?n2_p0 ; :material_name ?n2_p1 .\n" +
                "    } LIMIT 200 }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(q);
        System.out.println("REQUIRED-MERGE-SQL:\n" + sql);
        System.out.println("BOM_INSTANCES=" + countOccurrences(sql, "PARENT_BOM")
                + " MATERIAL_INSTANCES=" + countOccurrences(sql, "MATERIAL"));
        assertFalse("must not be EMPTY:\n" + sql, sql.contains("EMPTY"));
        // 3 CTE levels + one merged scan for ?n0 + one for ?n1
        assertTrue("same-source merge should collapse the attribute scans:\n" + sql,
                countOccurrences(sql, "PARENT_BOM") <= 6);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    @Test
    public void innerOptionalOnPathNodeAvoidsJoinBack() throws SQLException {
        String q = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n1_p0 ?n1_p5 WHERE {\n" +
                "  { SELECT DISTINCT ?n0 ?n1 ?n2 ?n1_p0 ?n1_p5 WHERE {\n" +
                "      ?n0 a :bom_item ;\n" +
                "          :parent_bom_item+ ?n1 ;\n" +
                "          :bom_item_id ?v0 ;\n" +
                "          path:maxDepth 2 .\n" +
                "      FILTER(?v0 = \"2\")\n" +
                "      ?n1 :has_component ?n2 ;\n" +
                "          :bom_version ?v1 .\n" +
                "      FILTER(?v1 = \"1\")\n" +
                "      OPTIONAL { ?n1 :material_code ?n1_p0 ; :bom_version ?n1_p5 . }\n" +
                "    } LIMIT 200 }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(q);
        System.out.println("INNER-OPT-SQL:\n" + sql);
        int joins = 0;
        int idx = 0;
        while ((idx = sql.indexOf("LEFT OUTER JOIN", idx)) >= 0) {
            joins++;
            idx += 15;
        }
        System.out.println("INNER_OPT_LEFT_JOIN=" + joins);
        int pathCols = 0;
        idx = 0;
        while ((idx = sql.indexOf("path_col_", idx)) >= 0) {
            pathCols++;
            idx += 9;
        }
        System.out.println("INNER_OPT_PATH_COL=" + pathCols);
        assertFalse("must not be EMPTY:\n" + sql, sql.contains("EMPTY"));
        assertFalse("path-node attributes must not be joined back:\n" + sql,
                sql.toUpperCase().contains("LEFT OUTER JOIN \"PARENT_BOM\""));
        assertTrue("expected the attributes to come from the path CTE:\n" + sql, pathCols > 0);
    }

    @Test
    public void mergedOptionalOnPathNode() throws SQLException {
        String q = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT DISTINCT ?n0 ?n1 ?n2 ?n1_p1 ?n1_p5 ?n2_p1 WHERE {\n" +
                "?n0 a :bom_item .\n?n1 a :bom_item .\n?n2 a :material .\n" +
                "?n0 :parent_bom_item+ ?n1 .\n?n0 path:maxDepth 2 .\n" +
                "?n1 :has_component ?n2 .\n" +
                "?n0 :bom_item_id ?v0_0 . FILTER(?v0_0 = \"2\")\n" +
                "?n1 :bom_version ?v1_1 . FILTER(?v1_1 = \"1\")\n" +
                "OPTIONAL {\n" +
                "  ?n1 :material_code ?n1_p0 ;\n" +
                "      :bom_item_id ?n1_p1 ;\n" +
                "      :bom_code ?n1_p2 ;\n" +
                "      :bom_version ?n1_p5 .\n" +
                "}\n" +
                "OPTIONAL { ?n2 :color ?n2_p1 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(q);
        System.out.println("MERGED-OPT-SQL:\n" + sql);
        int count = 0;
        int idx = 0;
        while ((idx = sql.indexOf("path_col_", idx)) >= 0) {
            count++;
            idx += 9;
        }
        System.out.println("MERGED_PATH_COL_COUNT=" + count);
        assertFalse("must not be EMPTY:\n" + sql, sql.contains("EMPTY"));
    }

    @Test
    public void fullQueryWithConstantStartOptionals() throws SQLException {
        String q = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT DISTINCT ?n0 ?n1 ?n2 " +
                "?n0_p0 ?n0_p1 ?n0_p2 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p2 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 " +
                "?n2_p0 ?n2_p1 ?n2_p2 ?n2_p3 ?n2_p4 ?n2_p5 ?n2_p6 ?n2_p7 ?n2_p8 ?n2_p9 ?n2_p10 ?n2_p11 ?n2_p12 ?n2_p13 " +
                "WHERE {\n" +
                " { SELECT DISTINCT ?n0 ?n1 ?n2 WHERE {\n" +
                "?n0 a :bom_item .\n?n1 a :bom_item .\n?n2 a :material .\n" +
                "?n0 :parent_bom_item+ ?n1 .\n?n0 path:maxDepth 2 .\n" +
                "?n1 :has_component ?n2 .\n" +
                "?n0 :bom_item_id ?v0_0 . FILTER(?v0_0 = \"2\")\n" +
                "?n1 :bom_version ?v1_1 . FILTER(?v1_1 = \"1\")\n" +
                " } LIMIT 200 }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :material_code ?n0_p0 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :bom_item_id ?n0_p1 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :bom_code ?n0_p2 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :unit ?n0_p3 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :process_code ?n0_p4 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :bom_version ?n0_p5 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :quantity ?n0_p6 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :parent_base_qty ?n0_p7 . }\n" +
                "OPTIONAL { <http://example.org/ontology#bom_item/2> :parent_material_code ?n0_p8 . }\n" +
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
                "OPTIONAL { ?n2 :color ?n2_p1 . }\n" +
                "OPTIONAL { ?n2 :category_code ?n2_p2 . }\n" +
                "OPTIONAL { ?n2 :safety_stock ?n2_p3 . }\n" +
                "OPTIONAL { ?n2 :is_critical ?n2_p4 . }\n" +
                "OPTIONAL { ?n2 :procurement_type ?n2_p5 . }\n" +
                "OPTIONAL { ?n2 :spec_width ?n2_p6 . }\n" +
                "OPTIONAL { ?n2 :spec_length ?n2_p7 . }\n" +
                "OPTIONAL { ?n2 :plant_code ?n2_p8 . }\n" +
                "OPTIONAL { ?n2 :base_unit ?n2_p9 . }\n" +
                "OPTIONAL { ?n2 :spec_thickness ?n2_p10 . }\n" +
                "OPTIONAL { ?n2 :density ?n2_p11 . }\n" +
                "OPTIONAL { ?n2 :material_type ?n2_p12 . }\n" +
                "OPTIONAL { ?n2 :material_desc ?n2_p13 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(q);
        System.out.println("FULL-SQL:\n" + sql);
        int pathCols = 0;
        int idx = 0;
        while ((idx = sql.indexOf("path_col_", idx)) >= 0) {
            pathCols++;
            idx += 9;
        }
        System.out.println("PATH_COL_COUNT=" + pathCols);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
        // The path-node optionals live in a different projection scope, so they must be
        // answered by joining the base table back (not silently dropped to NULL).
        assertTrue("expected the path-node attributes to be joined back:\n" + sql,
                sql.contains("LEFT OUTER JOIN"));
    }

    @Test
    public void optionalOnConstantStartIri() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n2 ?n0_p0 ?n1_p5 WHERE {\n" +
                "  { SELECT ?n0 ?n1 ?n2 WHERE {\n" +
                "      ?n0 a :bom_item ;\n" +
                "          :parent_bom_item+ ?n1 ;\n" +
                "          :bom_item_id ?v0 ;\n" +
                "          path:maxDepth 2 .\n" +
                "      FILTER(?v0 = \"2\")\n" +
                "      ?n1 :has_component ?n2 ;\n" +
                "          :bom_version ?v1 .\n" +
                "      FILTER(?v1 = \"1\")\n" +
                "    } LIMIT 200 }\n" +
                "  OPTIONAL { <http://example.org/ontology#bom_item/2> :material_code ?n0_p0 . }\n" +
                "  OPTIONAL { <http://example.org/ontology#bom_item/2> :bom_item_id ?n0_p1 . }\n" +
                "  OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("CONST-START-OPT-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
    }

    @Test
    @Ignore("待支持：对路径起点变量取 OPTIONAL 属性（LEFT JOIN 条件引用路径 relation 的 start_iri）")
    public void optionalOnStartVariableAttribute() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n0_p1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?v0 . FILTER(?v0 = \"2\")\n" +
                "  OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OPT-START-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
    }

    @Test
    public void optionalOnPathNodeAttributes() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n2 ?n1_p1 ?n1_p5 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n2 a :material .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n1 :has_component ?n2 .\n" +
                "  ?n0 :bom_item_id ?v0 . FILTER(?v0 = \"2\")\n" +
                "  ?n1 :bom_version ?v1 . FILTER(?v1 = \"1\")\n" +
                "  OPTIONAL { ?n1 :bom_item_id ?n1_p1 . }\n" +
                "  OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OPT-PATHNODE-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
    }

    @Test
    public void pathWithoutOptionalAttributes() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n2 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n2 a :material .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n1 :has_component ?n2 .\n" +
                "  ?n0 :bom_item_id ?v0 . FILTER(?v0 = \"2\")\n" +
                "  ?n1 :bom_version ?v1 . FILTER(?v1 = \"1\")\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("STRING-NOOPT-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
    }
}
