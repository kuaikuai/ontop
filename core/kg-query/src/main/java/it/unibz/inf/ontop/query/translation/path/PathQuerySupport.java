package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.RelationDefinition;

import java.util.Optional;

public interface PathQuerySupport {

    boolean isEnabled();

    Optional<PathSpec> getSpec(String predicateIri);

    RelationDefinition toRelation(String pathSql, String nodeKeyType);
}
