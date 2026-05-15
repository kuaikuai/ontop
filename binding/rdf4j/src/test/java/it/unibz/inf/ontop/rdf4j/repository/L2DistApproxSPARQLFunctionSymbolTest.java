package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * Test for l2_distance_approximate vector search function.
 * Tests SPARQL to SQL translation of vfn:l2DistApprox.
 *
 * SPARQL: vfn:l2DistApprox(?emb, "[0.1,0.2,0.3]"^^xsd:string)
 * SQL:    l2_distance_approximate(embedding, ARRAY[0.1,0.2,0.3])
 */
public class L2DistApproxSPARQLFunctionSymbolTest extends AbstractRDF4JTest {

    private static final String OBDA_FILE = "/employee/employee.obda";
    private static final String SQL_SCRIPT = "/employee/employee.sql";

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA(SQL_SCRIPT, OBDA_FILE);
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testL2DistApproxBIND() throws Exception {
        String sparql = "PREFIX vfn: <http://ontop/vectorfn/>\n" +
                "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?v WHERE {\n" +
                "    ?s :firstName ?emb .\n" +
                "    BIND(vfn:l2DistApprox(?emb, \"[0.1,0.2,0.3]\"^^xsd:string) AS ?v)\n" +
                "}";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== BIND SQL ===");
        System.out.println(sql);
        assertTrue(sql.toLowerCase().contains("l2_distance_approximate"));
    }

    @Test
    public void testL2DistApproxFILTER() throws Exception {
        String sparql = "PREFIX vfn: <http://ontop/vectorfn/>\n" +
                "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?v WHERE {\n" +
                "    ?s :firstName ?emb .\n" +
                "    FILTER(vfn:l2DistApprox(?emb, \"[0.1,0.2,0.3]\"^^xsd:string) > 0.8)\n" +
                "}";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== FILTER SQL ===");
        System.out.println(sql);
        assertTrue(sql.toLowerCase().contains("l2_distance_approximate"));
    }

    @Test
    public void testL2DistApproxOrderBy() throws Exception {
        String sparql = "PREFIX vfn: <http://ontop/vectorfn/>\n" +
                "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?v WHERE {\n" +
                "    ?s :firstName ?emb .\n" +
                "    BIND(vfn:l2DistApprox(?emb, \"[0.1,0.2,0.3]\"^^xsd:string) AS ?v)\n" +
                "} ORDER BY ?v LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== ORDER BY SQL ===");
        System.out.println(sql);
        assertTrue(sql.toLowerCase().contains("l2_distance_approximate"));
    }
}