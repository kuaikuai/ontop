package it.unibz.inf.ontop.dbschema;

import java.util.Optional;

public interface PathSpecRegistry {

    void upsert(PathSpec spec);

    void remove(String predicate);

    Optional<PathSpec> get(String predicate);

    long version();
}
