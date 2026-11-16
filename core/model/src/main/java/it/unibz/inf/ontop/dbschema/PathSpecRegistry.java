package it.unibz.inf.ontop.dbschema;

import java.util.Optional;
import java.util.Set;

public interface PathSpecRegistry {

    void upsert(PathSpec spec);

    void remove(String predicate);

    Optional<PathSpec> get(String predicate);

    Set<String> getPredicates();

    long version();
}
