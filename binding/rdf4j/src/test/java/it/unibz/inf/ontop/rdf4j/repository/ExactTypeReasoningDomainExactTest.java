package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertFalse;

public class ExactTypeReasoningDomainExactTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-domain/data.sql",
                "/exact-type-reasoning-domain/mapping.obda",
                "/exact-type-reasoning-domain/ontology.ttl",
                "/exact-type-reasoning-domain/exact.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactDropsDomainDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));

        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        assertFalse("exact mode should drop the domain-derived class mapping, leaving a single table:\n" + sql,
                sql.contains("union"));
    }

    @Test
    public void testExactWithPropertyAtom() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> ; <http://example.org/ontology#code> ?c }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/1",
                "http://example.org/ontology#a/2"));

        String sql = reformulateIntoNativeQuery(query).toLowerCase();
        assertFalse("exact mode must not include the domain-derived table:\n" + sql,
                sql.contains("ontology#b/"));
    }
}
