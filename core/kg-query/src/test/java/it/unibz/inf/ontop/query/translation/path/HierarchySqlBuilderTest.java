package it.unibz.inf.ontop.query.translation.path;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.dbschema.PathSpec;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(sql.contains("SELECT node_iri, depth, predecessor, start_iri FROM level_2"));
        assertTrue(sql.contains("UNION ALL SELECT node_iri, depth, predecessor, start_iri FROM level_3"));
    }

    @Test
    public void inSeedRendersInList() {
        SeedSource.Values inSeed = new SeedSource.Values(ImmutableList.of(
                "http://example.org/bom/bom_item/3517912884",
                "http://example.org/bom/bom_item/123"));
        String sql = new HierarchySqlBuilder().build(spec, inSeed, 1, 2, Optional.empty());
        assertTrue(sql.contains("bom_item_id IN (3517912884, 123)"));
        assertFalse(sql.contains("bom_item_id = 3517912884"));
    }

    @Test
    public void inSeedEscapesQuotesPerValue() {
        PathSpec.Self stringSpec = new PathSpec.Self(
                "http://example.org/bom/parent_bom_item",
                "ods.t", "material_code", "parent_material_code",
                "material_code", "VARCHAR", "http://example.org/bom/material/",
                java.util.Collections.emptyMap(), Optional.empty());
        SeedSource.Values inSeed = new SeedSource.Values(ImmutableList.of(
                "http://example.org/bom/material/a",
                "http://example.org/bom/material/b'c"));
        String sql = new HierarchySqlBuilder().build(stringSpec, inSeed, 1, 1, Optional.empty());
        assertTrue(sql.contains("material_code IN ('a', 'b''c')"));
    }

    @Test
    public void inSeedOverTheLimitIsRejected() {
        ImmutableList<String> iris = IntStream.rangeClosed(1, HierarchySqlBuilder.MAX_SEED_VALUES + 1)
                .mapToObj(i -> "http://example.org/bom/bom_item/" + i)
                .collect(ImmutableList.toImmutableList());
        SeedSource.Values inSeed = new SeedSource.Values(iris);
        assertThrows(IllegalArgumentException.class,
                () -> new HierarchySqlBuilder().build(spec, inSeed, 1, 2, Optional.empty()));
    }

    @Test
    public void emptyInSeedIsRejected() {
        SeedSource.Values inSeed = new SeedSource.Values(ImmutableList.of());
        assertThrows(IllegalArgumentException.class,
                () -> new HierarchySqlBuilder().build(spec, inSeed, 1, 2, Optional.empty()));
    }
}
