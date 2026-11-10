package it.unibz.inf.ontop.rdf4j.repository;

import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class ExactTypeReasoningMultiDomainFullTest extends AbstractRDF4JTest {

    @BeforeClass
    public static void before() throws IOException, SQLException {
        initOBDA("/exact-type-reasoning-multidomain/data.sql",
                "/exact-type-reasoning-multidomain/mapping.obda",
                "/exact-type-reasoning-multidomain/ontology.ttl",
                "/exact-type-reasoning-multidomain/full.properties");
    }

    @AfterClass
    public static void after() throws SQLException {
        release();
    }

    @Test
    public void testFullMaterialTypeWithSharedProperty() {
        String query = "SELECT DISTINCT ?v WHERE { " +
                "?v a <http://example.org/ontology#material> ; " +
                "<http://example.org/ontology#material_code> ?c . " +
                "FILTER(?c = \"1649607\") }";
        runQueryAndCompare(query, ImmutableSet.of(
                "http://example.org/ontology#material/1",
                "http://example.org/ontology#bom_item/3",
                "http://example.org/ontology#material_mould_output/5",
                "http://example.org/ontology#material_equipment_capability/7",
                "http://example.org/ontology#line_side_stock/9"));
    }
}
