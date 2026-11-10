package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningFullTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning/data.sql",
                "/exact-type-reasoning/mapping.obda",
                "/exact-type-reasoning/ontology.ttl",
                "/exact-type-reasoning/full.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testFullIncludesDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/3",
                "http://example.org/ontology#a/4",
                "http://example.org/ontology#b/1",
                "http://example.org/ontology#b/2"));
    }
}
