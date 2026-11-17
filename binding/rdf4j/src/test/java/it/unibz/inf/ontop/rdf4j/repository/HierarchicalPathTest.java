package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void perLevelLimitIsAppliedOnlyWhenRequested() throws SQLException {
        String withLimit = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  <http://example.org/ontology#bom_item/2> path:perLevelLimit 1 .\n" +
                "}";
        String sqlWithLimit = reformulateIntoNativeQuery(withLimit);
        System.out.println("PER-LEVEL-LIMIT-SQL:\n" + sqlWithLimit);
        assertTrue("expected a LIMIT on every level:\n" + sqlWithLimit,
                countOccurrences(sqlWithLimit, "LIMIT 1") >= 2);

        String withoutLimit = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "}";
        String sqlWithoutLimit = reformulateIntoNativeQuery(withoutLimit);
        assertFalse("no per-level LIMIT should be added by default:\n" + sqlWithoutLimit,
                sqlWithoutLimit.contains("LIMIT 1"));
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
    public void traversalFilterSupportsContains() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?code WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor path:traversalFilter true .\n" +
                "  ?ancestor :material_code ?code .\n" +
                "  FILTER(CONTAINS(?code, \"M\"))\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("CONTAINS-SQL:\n" + sql);
        assertTrue("expected per-level LIKE pruning:\n" + sql,
                sql.toUpperCase().contains("LIKE '%M%'"));
    }

    @Test
    public void traversalFilterPrunesEveryLevel() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?bom_version WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor path:traversalFilter true .\n" +
                "  ?ancestor :bom_version ?bom_version .\n" +
                "  FILTER(?bom_version = 1)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("PRUNE-SQL:\n" + sql);

        assertTrue("expected the pruning condition on every level:\n" + sql,
                sql.toLowerCase().contains("where")
                        && sql.toLowerCase().contains("bom_version"));

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString(1));
            assertEquals("1", rs.getString(2));
        }
    }

    @Test
    public void plainJoinHasCondition() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "SELECT ?m ?n WHERE {\n" +
                "  ?b a :bom_item ; :material_code ?m .\n" +
                "  ?mat a :material ; :material_code ?m ; :material_name ?n .\n" +
                "}";
        String sql = reformulateIntoNativeQuery(query);
        System.out.println("PLAIN-SQL:\n" + sql);
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
        }
    }

    @Test
    @Ignore("待支持：路径结果节点的常量属性约束（?n1 prop:bom_version \"1\"）")
    public void resultConstantConstraintWithVariableSeed() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid = \"2\")\n" +
                "  ?n1 :bom_version \"1\" .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("RESCONST-SQL:\n" + sql);
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            while (rs.next())
                System.out.println("ROW: " + rs.getString(1) + " / " + rs.getString(2));
        }
    }

    @Test
    public void optionalOnPathNodeAttributesDoesNotEmpty() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n0_p1 ?n1_p5 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?v0 . FILTER(?v0 = 2)\n" +
                "  OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "  OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OPT-ATTR-SQL:\n" + sql);
        assertTrue("must not be optimised to EMPTY:\n" + sql, !sql.contains("EMPTY"));
    }

    @Test
    public void outerOptionalOnSameKeyPropertyKeepsSeed() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n0_p1 WHERE {\n" +
                "  { SELECT ?n0 ?n1 WHERE {\n" +
                "      ?n0 a :bom_item ;\n" +
                "          :parent_bom_item+ ?n1 ;\n" +
                "          :bom_item_id ?bid ;\n" +
                "          path:maxDepth 2 .\n" +
                "      FILTER(?bid = 2)\n" +
                "    } LIMIT 200 }\n" +
                "  OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OUTER-OPT-SQL:\n" + sql);
        assertTrue("expected the start seed to be kept:\n" + sql,
                sql.contains("= 2"));
    }

    @Test
    public void sameAttributeOnStartAndPathNode() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0_p0 ?n1_p0 WHERE {\n" +
                "  ?n0 a :bom_item ; :parent_bom_item* ?n1 ; :bom_item_id ?bid ; path:maxDepth 2 .\n" +
                "  FILTER(?bid = 2)\n" +
                "  OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "  OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("SAMECOL-SQL:\n" + sql);
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= md.getColumnCount(); i++)
                    System.out.println("COL " + md.getColumnLabel(i) + " = " + rs.getString(i));
                System.out.println("---");
            }
        }
    }

    @Test
    @Ignore("待支持：路径起点属性直接绑常量（?n0 prop:bom_item_id \"3517912884\"）")
    public void constantBoundStartAndResultConstraint() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id \"2\" .\n" +
                "  ?n1 :bom_version \"1\" .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("CONST-SQL:\n" + sql);
    }

    @Test
    public void variableStartSeededByBomItemId() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?material_code WHERE {\n" +
                "  ?node a :bom_item ;\n" +
                "        :parent_bom_item* ?ancestor ;\n" +
                "        :bom_item_id ?bom_item_id ;\n" +
                "        path:maxDepth 2 .\n" +
                "  FILTER(?bom_item_id = 2)\n" +
                "  ?ancestor :material_code ?material_code .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("VAR-SQL:\n" + sql);

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
        }
    }

    @Test
    public void pathNodeJoinsAnotherRelation() throws SQLException {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT DISTINCT ?ancestor ?material_code ?material_name WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor path:traversalFilter true .\n" +
                "  ?ancestor :material_code ?material_code .\n" +
                "  ?material a :material ; :material_code ?material_code ; :material_name ?material_name .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("JOIN-SQL:\n" + sql);

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("M1", rs.getString(1));
            assertEquals("1", rs.getString(2));
            assertEquals("Mat1", rs.getString(3));
        }
    }
}
