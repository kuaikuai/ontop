package it.unibz.inf.ontop.dbschema.impl;

import com.google.common.collect.ImmutableMap;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PathSpecRegistryImpl implements PathSpecRegistry {

    private final AtomicReference<ImmutableMap<String, PathSpec>> snapshot =
            new AtomicReference<>(ImmutableMap.of());
    private final AtomicLong version = new AtomicLong();

    @Override
    public void upsert(PathSpec spec) {
        snapshot.updateAndGet(m -> ImmutableMap.<String, PathSpec>builder()
                .putAll(m)
                .put(spec.predicate(), spec)
                .build());
        version.incrementAndGet();
    }

    @Override
    public void remove(String predicate) {
        snapshot.updateAndGet(m -> {
            ImmutableMap.Builder<String, PathSpec> builder = ImmutableMap.builder();
            m.forEach((k, v) -> {
                if (!k.equals(predicate))
                    builder.put(k, v);
            });
            return builder.build();
        });
        version.incrementAndGet();
    }

    @Override
    public Optional<PathSpec> get(String predicate) {
        return Optional.ofNullable(snapshot.get().get(predicate));
    }

    @Override
    public long version() {
        return version.get();
    }
}
