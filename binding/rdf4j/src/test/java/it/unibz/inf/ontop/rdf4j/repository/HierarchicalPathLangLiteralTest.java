package it.unibz.inf.ontop.rdf4j.repository;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Language-tagged properties must be excluded from path attribute sinking
 * (see docs/superpowers/specs/2026-11-17-property-datatype-for-path-sinking-design.md §4.5):
 * a sunk lang literal would lose its language tag, and was the source of the
 * swallowed-pattern bug of the first implementation.
 */
public class HierarchicalPathLangLiteralTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/parent-bom-item-lang/data.sql",
                "/parent-bom-item-lang/mapping.obda",
                "/parent-bom-item-lang/ontology.ttl");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void langTaggedAttributeIsNotSunkAndKeepsItsTag() throws Exception {
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?note WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :note ?note .\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("LANG-SQL:\n" + sql);

        assertFalse("the query must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
        /*
         * If :note were sunk, its column would be carried by every level of the
         * recursive CTE (at least 4 occurrences). Not being sunk, it is only
         * referenced by the join-back to the base table.
         */
        assertTrue(":note must not be sunk into the path CTE:\n" + sql,
                countOccurrences(sql.toUpperCase(), "\"NOTE\"") <= 2);

        TupleQueryResult result = evaluate(query);
        List<String> notes = new ArrayList<>();
        while (result.hasNext()) {
            BindingSet bindingSet = result.next();
            Value note = bindingSet.getValue("note");
            assertTrue("the note must be a literal: " + note, note instanceof Literal);
            Literal literal = (Literal) note;
            assertTrue("the note literal must keep its language tag: " + literal,
                    literal.getLanguage().isPresent());
            assertEquals("en", literal.getLanguage().get());
            notes.add(literal.stringValue());
        }
        result.close();

        // bom_item/2 has a single ancestor within 2 levels: bom_item/1 ("root part")
        assertEquals(java.util.Arrays.asList("root part"), notes);
    }

    @Test
    public void integerAttributeOnTheSameSpecIsStillSunk() throws Exception {
        // :bom_version is not language-tagged: the exclusion of :note must not affect it
        String query = "PREFIX : <http://example.org/ontology#>\n" +
                "PREFIX path: <http://ontop.org/path#>\n" +
                "SELECT ?ancestor ?bv WHERE {\n" +
                "  <http://example.org/ontology#bom_item/2> :parent_bom_item* ?ancestor .\n" +
                "  <http://example.org/ontology#bom_item/2> path:maxDepth 2 .\n" +
                "  ?ancestor :bom_version ?bv .\n" +
                "  FILTER(?bv = 1)\n" +
                "}";

        String sql = reformulateIntoNativeQuery(query);
        System.out.println("LANG-INT-SQL:\n" + sql);
        assertFalse("must not be optimised to EMPTY:\n" + sql, sql.contains("EMPTY"));
        assertTrue("bom_version must be sunk (carried by every CTE level):\n" + sql,
                countOccurrences(sql.toUpperCase(), "\"BOM_VERSION\"") >= 3);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + 1))
            count++;
        return count;
    }
}
