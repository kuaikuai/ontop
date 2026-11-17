package it.unibz.inf.ontop.spec.impl;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import it.unibz.inf.ontop.dbschema.DBParameters;
import it.unibz.inf.ontop.spec.mapping.Mapping;
import it.unibz.inf.ontop.spec.ontology.ClassifiedTBox;
import it.unibz.inf.ontop.spec.OBDASpecification;

import java.util.Map;
import java.util.Set;


public class OBDASpecificationImpl implements OBDASpecification {

    private final Mapping mapping;
    private final DBParameters dbParameters;
    private final ClassifiedTBox saturatedTBox;
    private final Map<String, String> propertyDatatypes;
    private final Set<String> langLiteralProperties;

    @Inject
    private OBDASpecificationImpl(@Assisted Mapping saturatedMapping,
                                  @Assisted DBParameters dbParameters,
                                  @Assisted ClassifiedTBox saturatedTBox,
                                  @Assisted Map<String, String> propertyDatatypes,
                                  @Assisted Set<String> langLiteralProperties) {
        this.mapping = saturatedMapping;
        this.dbParameters = dbParameters;
        this.saturatedTBox = saturatedTBox;
        this.propertyDatatypes = propertyDatatypes;
        this.langLiteralProperties = langLiteralProperties;
    }

    @Override
    public Mapping getSaturatedMapping() { return mapping; }

    @Override
    public DBParameters getDBParameters() {
        return dbParameters;
    }

    @Override
    public ClassifiedTBox getSaturatedTBox() {
        return saturatedTBox;
    }

    @Override
    public Map<String, String> getPropertyDatatypes() { return propertyDatatypes; }

    @Override
    public Set<String> getLangLiteralProperties() { return langLiteralProperties; }
}
