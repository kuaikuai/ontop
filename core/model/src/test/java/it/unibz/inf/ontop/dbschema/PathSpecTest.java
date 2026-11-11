package it.unibz.inf.ontop.dbschema;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathSpecTest {

    @Test
    public void selfSpecKeepsColumns() {
        PathSpec.Self spec = new PathSpec.Self(
                "http://example.org/bom/parent_bom_item",
                "ods.t", "material_code", "parent_material_code",
                "bom_item_id", "http://example.org/bom/bom_item/",
                Optional.empty());
        assertEquals("material_code", spec.joinKeyColumn());
        assertEquals("parent_material_code", spec.parentJoinKeyColumn());
        assertEquals("bom_item_id", spec.nodeKeyColumn());
    }
}
