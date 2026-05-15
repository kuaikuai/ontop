package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * Test for COALESCE with variables from OPTIONAL patterns.
 * Simulates the user's actual query structure.
 */
public class CoalesceOptionalTest extends AbstractRDF4JTest {

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

    /**
     * Test: COALESCE with OPTIONAL sourced variable + constant
     * This simulates: COALESCE(?formulaScore, 0) where ?formulaScore comes from OPTIONAL
     */
    @Test
    public void testCoalesceWithOptionalVariable() throws Exception {
        // Simulates: OPTIONAL { ?pm prop:formula ?formula . BIND(8 AS ?formulaScore) }
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT (COALESCE(?formulaScore, 0) + 10 AS ?totalScore) WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    OPTIONAL { ?s :status ?status . BIND(?status AS ?formulaScore) }\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== COALESCE with OPTIONAL Variable SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }

    /**
     * Test: Multiple COALESCE with addition
     */
    @Test
    public void testMultipleCoalesceWithAddition() throws Exception {
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT (COALESCE(?score1, 0) + COALESCE(?score2, 0) AS ?totalScore) WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    OPTIONAL { ?s :status ?status . BIND(?status AS ?score1) }\n" +
                "    OPTIONAL { ?s :country ?country . BIND(5 AS ?score2) }\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== Multiple COALESCE with Addition SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }

    /**
     * Test: BIND with arithmetic on OPTIONAL variable
     * This is exactly like the user's query: BIND(?formulaScore * 2 AS ?my)
     */
    @Test
    public void testBindArithmeticOnOptionalVariable() throws Exception {
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?result WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    OPTIONAL { ?s :status ?status . BIND(?status * 2 AS ?score) }\n" +
                "    BIND(COALESCE(?score, 0) AS ?result)\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== BIND Arithmetic on OPTIONAL Variable SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }
}