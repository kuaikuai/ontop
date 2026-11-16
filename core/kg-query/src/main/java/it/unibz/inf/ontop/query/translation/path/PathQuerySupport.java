package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.RelationDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public interface PathQuerySupport {

    boolean isEnabled();

    Optional<PathSpec> getSpec(String predicateIri);

    default Set<String> getRegisteredPredicates() {
        return Collections.emptySet();
    }

    RelationDefinition toRelation(String pathSql, String nodeKeyType, Collection<String> nodeColumns);
}
