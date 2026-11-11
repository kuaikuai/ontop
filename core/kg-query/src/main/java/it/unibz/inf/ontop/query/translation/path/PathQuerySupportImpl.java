package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.DBParameters;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;
import it.unibz.inf.ontop.dbschema.QuotedIDFactory;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.AbstractRelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.PathRelationDefinition;
import it.unibz.inf.ontop.model.type.DBTermType;

import java.util.Optional;

public class PathQuerySupportImpl implements PathQuerySupport {

    private final DBParameters dbParameters;
    private final PathSpecRegistry registry;

    public PathQuerySupportImpl(DBParameters dbParameters, PathSpecRegistry registry) {
        this.dbParameters = dbParameters;
        this.registry = registry;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Optional<PathSpec> getSpec(String predicateIri) {
        return registry.get(predicateIri);
    }

    @Override
    public RelationDefinition toRelation(String pathSql) {
        RelationDefinition.AttributeListBuilder builder = AbstractRelationDefinition.attributeListBuilder();
        QuotedIDFactory ids = dbParameters.getQuotedIDFactory();
        DBTermType text = dbParameters.getDBTypeFactory().getAbstractRootDBType();
        String typeName = text.getName();
        builder.addAttribute(ids.createAttributeID("node_iri"), text, typeName, true);
        builder.addAttribute(ids.createAttributeID("depth"), text, typeName, false);
        builder.addAttribute(ids.createAttributeID("predecessor"), text, typeName, true);
        return new PathRelationDefinition(pathSql, builder);
    }
}
