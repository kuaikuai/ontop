package it.unibz.inf.ontop.spec;


import it.unibz.inf.ontop.dbschema.DBParameters;
import it.unibz.inf.ontop.spec.mapping.Mapping;
import it.unibz.inf.ontop.spec.ontology.ClassifiedTBox;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * See SpecificationFactory for creating a new instance.
 */
public interface OBDASpecification {

    Mapping getSaturatedMapping();

    DBParameters getDBParameters();

    ClassifiedTBox getSaturatedTBox();

    /**
     * (table, property IRI) -> declared RDF datatype IRI of the property,
     * extracted from the unsaturated mapping before saturation.
     * See docs/superpowers/specs/2026-11-17-property-datatype-for-path-sinking-design.md
     */
    default Map<String, String> getPropertyDatatypes() {
        return Collections.emptyMap();
    }

    /**
     * (table, property IRI) whose values carry a language tag.
     * Such properties must not be sunk into the path CTE.
     */
    default Set<String> getLangLiteralProperties() {
        return Collections.emptySet();
    }
}
