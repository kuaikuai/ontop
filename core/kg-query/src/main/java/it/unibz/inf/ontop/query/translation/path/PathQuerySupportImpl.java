package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.dbschema.DBParameters;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;
import it.unibz.inf.ontop.dbschema.QuotedIDFactory;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.AbstractRelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.PathRelationDefinition;
import it.unibz.inf.ontop.model.type.DBTypeFactory;
import it.unibz.inf.ontop.model.type.DBTermType;

import java.util.Collection;
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
    public RelationDefinition toRelation(String pathSql, String nodeKeyType, Collection<String> nodeColumns) {
        RelationDefinition.AttributeListBuilder builder = AbstractRelationDefinition.attributeListBuilder();
        QuotedIDFactory ids = dbParameters.getQuotedIDFactory();
        DBTypeFactory types = dbParameters.getDBTypeFactory();
        DBTermType stringType = types.getDBStringType();
        DBTermType keyType = stringType;
        if (nodeKeyType != null) {
            try {
                DBTermType derived = types.getDBTermType(nodeKeyType);
                if (derived != null)
                    keyType = derived;
            } catch (RuntimeException ignored) {
            }
        }
        builder.addAttribute(ids.createAttributeID("node_iri"), keyType, keyType.getName(), true);
        builder.addAttribute(ids.createAttributeID("depth"), stringType, stringType.getName(), false);
        builder.addAttribute(ids.createAttributeID("predecessor"), keyType, keyType.getName(), true);
        builder.addAttribute(ids.createAttributeID("start_iri"), keyType, keyType.getName(), true);
        for (String column : nodeColumns) {
            builder.addAttribute(ids.createAttributeID(column), stringType, stringType.getName(), true);
        }
        return new PathRelationDefinition(pathSql, builder);
    }
}
