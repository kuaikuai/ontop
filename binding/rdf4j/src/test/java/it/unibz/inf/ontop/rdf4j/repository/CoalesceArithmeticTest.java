package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * Test for COALESCE with arithmetic on OPTIONAL variables.
 * Reproduces the exact pattern from user's query:
 * BIND(?formulaScore * 2 AS ?my) where formulaScore comes from OPTIONAL
 */
public class CoalesceArithmeticTest extends AbstractRDF4JTest {

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
     * Test: BIND with arithmetic on variable from OPTIONAL (like user's query)
     * BIND(?formulaScore * 2 AS ?my) where formulaScore comes from OPTIONAL
     */
    @Test
    public void testBindArithmeticOnOptionalVariable() throws Exception {
        // This mimics: OPTIONAL { ?pm prop:formula ?formula . BIND(?formula * 8 AS ?formulaScore) }
        // Then: BIND(?formulaScore * 2 AS ?my)
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?my WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    OPTIONAL { ?s :status ?status . BIND(?status * 8 AS ?formulaScore) }\n" +
                "    BIND(COALESCE(?formulaScore, 0) * 2 AS ?my)\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== COALESCE * Arithmetic SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }

    /**
     * Test: Multiple OPTIONALs with score calculation (like user's full query)
     */
    @Test
    public void testMultipleScoresWithCoalesce() throws Exception {
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT ?totalScore WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    OPTIONAL { ?s :status ?status . BIND(?status * 2 AS ?rankScore) }\n" +
                "    OPTIONAL { ?s :country ?country . BIND(5 AS ?catgScore) }\n" +
                "    BIND(COALESCE(?rankScore, 0) + COALESCE(?catgScore, 0) AS ?totalScore)\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== Multiple COALESCE + Addition SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }
}