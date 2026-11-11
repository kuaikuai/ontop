package it.unibz.inf.ontop.dbschema;

import it.unibz.inf.ontop.dbschema.impl.PathSpecRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathSpecRegistryImplTest {

    @Test
    public void upsertIncrementsVersionAndReplaces() {
        PathSpecRegistry registry = new PathSpecRegistryImpl();
        long v0 = registry.version();
        PathSpec.Self spec = new PathSpec.Self("p", "t", "a", "b", "id", "pref", Optional.empty());
        registry.upsert(spec);
        assertEquals(v0 + 1, registry.version());
        assertSame(spec, registry.get("p").orElseThrow());
    }

    @Test
    public void remove() {
        PathSpecRegistry registry = new PathSpecRegistryImpl();
        registry.upsert(new PathSpec.Self("p", "t", "a", "b", "id", "pref", Optional.empty()));
        registry.remove("p");
        assertTrue(registry.get("p").isEmpty());
    }
}
