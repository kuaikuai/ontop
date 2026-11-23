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
    public void sunkIntegerAttributeSupportsNumericFilter() throws SQLException {
        // bom_version is declared ^^xsd:integer in the mapping: the sunk attribute must
        // keep that datatype so that a numeric FILTER matches. When everything was sunk
        // as xsd:string this query was optimised to EMPTY (string vs integer type error).
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?bv WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :bom_version ?bv .\n" +
                "  FILTER(?bv = 1)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("INT-FILTER-SQL:\n" + sql);
        assertTrue("expected bom_version to be carried by the path CTE:\n" + sql,
                sql.toLowerCase().contains("bom_version"));
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));

        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue("expected the parent with bom_version = 1", rs.next());
            assertEquals("1", rs.getString(1));
            assertEquals("1", rs.getString(2));
            assertFalse("expected a single ancestor", rs.next());
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
    public void optionalsInsideSubselectOnStartKeyProperty() throws SQLException {
        // Production shape: path + seed filter + attribute OPTIONals all INSIDE the subselect
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n0_p0 ?n0_p1 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 ?n2 ?n2_p0 WHERE {\n" +
                "  { SELECT DISTINCT ?n0 ?n1 ?n0_p0 ?n0_p1 ?n0_p3 ?n0_p4 ?n0_p5 ?n0_p6 ?n0_p7 ?n0_p8 " +
                "?n1_p0 ?n1_p1 ?n1_p3 ?n1_p4 ?n1_p5 ?n1_p6 ?n1_p7 ?n1_p8 ?n2 WHERE {\n" +
                "      ?n0 a :bom_item .\n" +
                "      ?n1 a :bom_item .\n" +
                "      ?n0 :parent_bom_item+ ?n1 .\n" +
                "      ?n0 path:maxDepth 2 .\n" +
                "      ?n0 :bom_item_id ?v0 . FILTER(?v0 = 2)\n" +
                "      ?n1 path:traversalFilter true .\n" +
                "      ?n1 :bom_version ?v1 . FILTER(?v1 = \"1\")\n" +
                "      OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "      OPTIONAL { ?n0 :bom_item_id ?n0_p1 . }\n" +
                "      OPTIONAL { ?n0 :parent_material_code ?n0_p3 . }\n" +
                "      OPTIONAL { ?n0 :bom_version ?n0_p5 . }\n" +
                "      OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "      OPTIONAL { ?n1 :bom_item_id ?n1_p1 . }\n" +
                "      OPTIONAL { ?n1 :parent_material_code ?n1_p3 . }\n" +
                "      OPTIONAL { ?n1 :bom_version ?n1_p5 . }\n" +
                "      ?n1 :material_code ?n1mc .\n" +
                "      ?n2 a :material .\n" +
                "      ?n2 :material_code ?n1mc .\n" +
                "    } LIMIT 1000 }\n" +
                "  OPTIONAL { ?n2 :material_name ?n2_p0 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("INSIDE-OPT-SQL:\n" + sql);
        assertTrue("must not be EMPTY:\n" + sql, !sql.contains("EMPTY"));
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
    public void variableStartSeededByInListTraversesFromEverySeed() throws SQLException {
        // items 2 and 3 both have parent material M1 -> item 1 is their only ancestor
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid IN (2, 3))\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("IN-SEED-SQL:\n" + sql);

        assertTrue("expected the seed list to be rendered as IN:\n" + sql,
                sql.contains("IN (2, 3)"));

        java.util.Set<String> rows = new java.util.TreeSet<>();
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            while (rs.next())
                rows.add(rs.getString(1) + " -> " + rs.getString(2));
        }
        java.util.Set<String> expected = new java.util.TreeSet<>();
        expected.add("2 -> 1");
        expected.add("3 -> 1");
        assertEquals("expected one ancestor row per seed:\n" + sql, expected, rows);
    }

    @Test
    public void variableStartSeededByOrOfEqualities() throws SQLException {
        // FILTER(?x = a || ?x = b) must be recognised as the same seed set as IN (a, b)
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid = 2 || ?bid = 3)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OR-SEED-SQL:\n" + sql);

        assertTrue("expected the Or seed to be rendered as IN:\n" + sql,
                sql.contains("IN (2, 3)"));

        java.util.Set<String> rows = new java.util.TreeSet<>();
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            while (rs.next())
                rows.add(rs.getString(1) + " -> " + rs.getString(2));
        }
        java.util.Set<String> expected = new java.util.TreeSet<>();
        expected.add("2 -> 1");
        expected.add("3 -> 1");
        assertEquals(expected, rows);
    }

    @Test
    public void singleValueInListKeepsTheConstantPin() throws SQLException {
        // Backward compatibility: a single seed value keeps the ground-constant start
        // (path_start_raw pin), so it must NOT be rendered as an IN list.
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid IN (2))\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("SINGLE-IN-SQL:\n" + sql);

        assertTrue("expected the single seed to stay a constant pin:\n" + sql,
                sql.contains("= 2"));
        assertFalse("a single seed value must not be rendered as IN:\n" + sql,
                sql.contains("IN ("));

        // The start is pinned to a ground constant, so it is not projected by the
        // raw target SQL: the remaining column is the ancestor (item 1).
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    public void andOfInAndEqualityIntersectsTheSeeds() throws SQLException {
        // FILTER(?x IN (a, b) && ?x = b) is the single seed b
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid IN (2, 3) && ?bid = 3)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("AND-IN-SQL:\n" + sql);

        assertTrue("expected the intersection to be pinned as a single constant:\n" + sql,
                sql.contains("= 3"));

        // The pin drops the start from the raw target SQL: the ancestor is item 1.
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    public void separateInAndEqualityFiltersIntersectTheSeeds() throws SQLException {
        // Two independent FILTER statements on the same id variable are intersected
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?bid .\n" +
                "  FILTER(?bid IN (2, 3))\n" +
                "  FILTER(?bid = 2)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("SEP-IN-SQL:\n" + sql);

        assertTrue("expected the intersection to be pinned as a single constant:\n" + sql,
                sql.contains("= 2"));

        // The pin drops the start from the raw target SQL: the ancestor is item 1.
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString(1));
            assertFalse(rs.next());
        }
    }

    @Test
    public void optionalStartAttributesWithInSeeds() throws SQLException {
        // Multi-seed queries have no ground start constant: the start-node attribute
        // join-backs must bind through the start key variable (LEFT JOIN join with
        // start_iri) instead of the pin, and must not be optimised to EMPTY.
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?n0 ?n1 ?n0_p0 ?n1_p0 WHERE {\n" +
                "  ?n0 a :bom_item .\n" +
                "  ?n1 a :bom_item .\n" +
                "  ?n0 :parent_bom_item+ ?n1 .\n" +
                "  ?n0 path:maxDepth 2 .\n" +
                "  ?n0 :bom_item_id ?v0 . FILTER(?v0 IN (2, 3))\n" +
                "  OPTIONAL { ?n0 :material_code ?n0_p0 . }\n" +
                "  OPTIONAL { ?n1 :material_code ?n1_p0 . }\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("OPT-IN-SQL:\n" + sql);
        assertTrue("must not be optimised to EMPTY:\n" + sql, !sql.contains("EMPTY"));

        // The raw target SQL does not project the SPARQL variables one-to-one (the
        // IRI template columns are decomposed), so assert on the value domain: every
        // row must carry the ancestor material M1 plus the material of one of the
        // seeds (M2 for item 2, M3 for item 3), and both seeds must occur.
        java.util.Set<String> startMaterials = new java.util.TreeSet<>();
        int rowCount = 0;
        try (ResultSet rs = getSQLConnection().createStatement().executeQuery(sql)) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                rowCount++;
                java.util.Set<String> rowValues = new java.util.TreeSet<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String value = rs.getString(i);
                    if (value != null)
                        rowValues.add(value);
                }
                assertTrue("every row must contain the ancestor material M1:\n" + sql,
                        rowValues.contains("M1"));
                assertTrue("every row must contain exactly one seed material (M2 or M3):\n" + sql,
                        rowValues.contains("M2") != rowValues.contains("M3"));
                if (rowValues.contains("M2"))
                    startMaterials.add("M2");
                if (rowValues.contains("M3"))
                    startMaterials.add("M3");
            }
        }
        assertTrue("expected at least one row per seed:\n" + sql, rowCount >= 2);
        assertEquals("expected both seeds to contribute rows:\n" + sql,
                new java.util.TreeSet<>(java.util.Arrays.asList("M2", "M3")), startMaterials);
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
