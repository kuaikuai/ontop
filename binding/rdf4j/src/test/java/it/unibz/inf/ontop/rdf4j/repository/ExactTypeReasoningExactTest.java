package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;

public class ExactTypeReasoningExactTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning/data.sql",
                "/exact-type-reasoning/mapping.obda",
                "/exact-type-reasoning/ontology.ttl",
                "/exact-type-reasoning/exact.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testExactExcludesDerivedTypes() {
        String query = "SELECT DISTINCT ?v WHERE { ?v a <http://example.org/ontology#A> }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#a/3",
                "http://example.org/ontology#a/4"));
    }

    @Test
    public void testExactVariableTypeOnlyExplicit() {
        String query = "SELECT DISTINCT ?v ?t WHERE { ?v a ?t }";
        ImmutableSet.Builder<String> bindings = ImmutableSet.builder();
        for (ImmutableMap<String, String> row : executeQuery(query)) {
            bindings.add(row.get("v") + " " + row.get("t"));
        }
        assertEquals(ImmutableSet.of(
                "http://example.org/ontology#b/1 http://example.org/ontology#B",
                "http://example.org/ontology#b/2 http://example.org/ontology#B",
                "http://example.org/ontology#a/3 http://example.org/ontology#A",
                "http://example.org/ontology#a/4 http://example.org/ontology#A"),
                bindings.build());
    }
}
