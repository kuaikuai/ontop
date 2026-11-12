package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HierarchySqlBuilderTest {

    private final PathSpec.Self spec = new PathSpec.Self(
            "http://example.org/bom/parent_bom_item",
            "ods.t", "material_code", "parent_material_code",
            "bom_item_id", "BIGINT", "http://example.org/bom/bom_item/",
            java.util.Collections.emptyMap(), Optional.empty());

    private final SeedSource.Constant seed =
            new SeedSource.Constant("http://example.org/bom/bom_item/3517912884");

    @Test
    public void buildsLevelsAndUnionAll() {
        String sql = new HierarchySqlBuilder().build(spec, seed, 1, 2, Optional.empty());
        assertTrue(sql.contains("'0' AS depth"));
        assertTrue(sql.contains("'1' AS depth"));
        assertTrue(sql.contains("'2' AS depth"));
        assertTrue(sql.contains("UNION ALL"));
        assertTrue(sql.contains("material_code"));
        assertFalse(sql.contains("'3' AS depth"));
        assertTrue(sql.contains("bom_item_id AS node_iri"));
        assertFalse(sql.contains("CONCAT('', "));
        assertTrue(sql.contains("bom_item_id = 3517912884"));
    }

    @Test
    public void minDepthStartsFromTheRequestedLevel() {
        String sql = new HierarchySqlBuilder().build(spec, seed, 2, 3, Optional.empty());
        assertTrue(sql.contains("WITH"));
        assertTrue(sql.contains("SELECT node_iri, depth, predecessor FROM level_2"));
        assertTrue(sql.contains("UNION ALL SELECT node_iri, depth, predecessor FROM level_3"));
    }
}
