package it.unibz.inf.ontop.dbschema.impl;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.dbschema.ForeignKeyConstraint;
import it.unibz.inf.ontop.dbschema.FunctionalDependency;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.dbschema.UniqueConstraint;

public class PathRelationDefinition extends AbstractRelationDefinition {

    public PathRelationDefinition(String pathSql, RelationDefinition.AttributeListBuilder builder) {
        super("(" + pathSql + ")", builder);
    }

    @Override
    public ImmutableList<UniqueConstraint> getUniqueConstraints() {
        return ImmutableList.of();
    }

    @Override
    public ImmutableList<FunctionalDependency> getOtherFunctionalDependencies() {
        return ImmutableList.of();
    }

    @Override
    public ImmutableList<ForeignKeyConstraint> getForeignKeys() {
        return ImmutableList.of();
    }
}
