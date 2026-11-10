package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningSubPropertyTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-subproperty/data.sql",
                "/exact-type-reasoning-subproperty/mapping.obda",
                "/exact-type-reasoning-subproperty/ontology.ttl",
                "/exact-type-reasoning-subproperty/exact.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testSubPropertyReasoningPreservedInExact() {
        String query = "SELECT DISTINCT ?v WHERE { ?v <http://example.org/ontology#q> ?o }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#s/1",
                "http://example.org/ontology#s/2"));
    }
}
