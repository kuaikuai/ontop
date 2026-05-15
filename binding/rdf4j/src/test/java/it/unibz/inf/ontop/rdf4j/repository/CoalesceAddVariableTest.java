package it.unibz.inf.ontop.rdf4j.repository;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * Test for COALESCE with variables and addition.
 * Reproduces the issue: COALESCE(?score,0) + ?age
 */
public class CoalesceAddVariableTest extends AbstractRDF4JTest {

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
    public void testCoalesceAddVariables() throws Exception {
        String sparql = "PREFIX : <http://employee.example.org/voc#>\n" +
                "SELECT (COALESCE(?score,0) + ?age AS ?recycleScore) WHERE {\n" +
                "    ?s :firstName ?name .\n" +
                "    BIND(1 AS ?score)\n" +
                "    BIND(10 AS ?age)\n" +
                "} LIMIT 10";
        String sql = reformulateIntoNativeQuery(sparql);
        System.out.println("=== COALESCE + Variables SQL ===");
        System.out.println(sql);
        assertNotNull(sql);
    }
}