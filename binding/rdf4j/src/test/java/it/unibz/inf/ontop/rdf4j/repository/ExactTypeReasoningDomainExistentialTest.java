package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningDomainExistentialTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-domain/data.sql",
                "/exact-type-reasoning-domain/mapping.obda",
                "/exact-type-reasoning-domain/ontology.ttl",
                "/exact-type-reasoning-domain/existential.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactWithPropertyAtomUnderExistentialReasoning() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> ; <http://example.org/ontology#code> ?c }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));
    }
}
