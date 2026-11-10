package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertTrue;

public class ExactTypeReasoningDomainFullTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-domain/data.sql",
                "/exact-type-reasoning-domain/mapping.obda",
                "/exact-type-reasoning-domain/ontology.ttl",
                "/exact-type-reasoning-domain/full.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testFullIncludesDomainDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2",
                "http://example.org/ontology#b/3",
                "http://example.org/ontology#b/4"));

        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        assertTrue("full mode should keep the union of literal and domain-derived class mappings:\n" + sql,
                sql.contains("union"));
    }
}
