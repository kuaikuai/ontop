package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertFalse;

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
