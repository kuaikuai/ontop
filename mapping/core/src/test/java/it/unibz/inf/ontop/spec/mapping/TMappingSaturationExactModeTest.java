package it.unibz.inf.ontop.spec.mapping;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Injector;
import it.unibz.inf.ontop.dbschema.NamedRelationDefinition;
import it.unibz.inf.ontop.dbschema.impl.OfflineMetadataProviderBuilder;
import it.unibz.inf.ontop.injection.OntopMappingConfiguration;
import it.unibz.inf.ontop.injection.OntopModelSettings;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.node.ConstructionNode;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import it.unibz.inf.ontop.iq.node.UnionNode;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.model.atom.RDFAtomPredicate;
import it.unibz.inf.ontop.model.template.Template;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.vocabulary.RDF;
import it.unibz.inf.ontop.spec.mapping.transformer.MappingSaturator;
import it.unibz.inf.ontop.spec.ontology.OClass;
import it.unibz.inf.ontop.spec.ontology.ObjectPropertyExpression;
import it.unibz.inf.ontop.spec.ontology.Ontology;
import it.unibz.inf.ontop.spec.ontology.OntologyBuilder;
import it.unibz.inf.ontop.spec.ontology.impl.OntologyBuilderImpl;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import org.apache.commons.rdf.api.IRI;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static it.unibz.inf.ontop.utils.MappingTestingTools.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TMappingSaturationExactModeTest {

    private static final NamedRelationDefinition T1;
    private static final NamedRelationDefinition T2;

    private static final Variable X = TERM_FACTORY.getVariable("x");
    private static final Variable S = TERM_FACTORY.getVariable("s");
    private static final Variable P = TERM_FACTORY.getVariable("p");
    private static final Variable O = TERM_FACTORY.getVariable("o");

    private static final ImmutableList<Template.Component> TEMPLATE_B =
            Template.builder().string("http://example.org/b/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_A =
            Template.builder().string("http://example.org/a/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_S =
            Template.builder().string("http://example.org/s/").placeholder().build();
    private static final ImmutableList<Template.Component> TEMPLATE_O =
            Template.builder().string("http://example.org/o/").placeholder().build();

    private static final IRI CLASS_A = RDF_FACTORY.createIRI("http://example.org/ontology#A");
    private static final IRI CLASS_B = RDF_FACTORY.createIRI("http://example.org/ontology#B");
    private static final IRI PROP_P = RDF_FACTORY.createIRI("http://example.org/ontology#p");
    private static final IRI PROP_Q = RDF_FACTORY.createIRI("http://example.org/ontology#q");

    static {
        OfflineMetadataProviderBuilder builder = createMetadataProviderBuilder();
        T1 = builder.createDatabaseRelation("t1", "id",
                builder.getDBTypeFactory().getDBLargeIntegerType(), false);
        T2 = builder.createDatabaseRelation("t2", "id",
                builder.getDBTypeFactory().getDBLargeIntegerType(), false);
    }

    @Test
    public void testExactKeepsOnlyLiteralClassMappings() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        OClass a = builder.declareClass(CLASS_A);
        OClass b = builder.declareClass(CLASS_B);
        builder.addSubClassOfAxiom(b, a);
        Ontology ontology = builder.build();

        IQ bMapping = classMapping(TEMPLATE_B, CLASS_B, T1);
        IQ aMapping = classMapping(TEMPLATE_A, CLASS_A, T2);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(
                new MappingAssertion(bMapping, null),
                new MappingAssertion(aMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) aMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("exact")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        IQ classA = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_A));
        IQ classB = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_B));

        assertNotNull(classA, "literal a:A mapping must be kept in exact mode");
        assertNotNull(classB, "literal a:B mapping must be kept in exact mode");
        assertFalse(classA.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "exact a:A must not contain the B-derived mapping");
        assertFalse(classB.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "exact a:B must stay a single mapping");
    }

    @Test
    public void testFullMergesDerivedClassMapping() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        OClass a = builder.declareClass(CLASS_A);
        OClass b = builder.declareClass(CLASS_B);
        builder.addSubClassOfAxiom(b, a);
        Ontology ontology = builder.build();

        IQ bMapping = classMapping(TEMPLATE_B, CLASS_B, T1);
        IQ aMapping = classMapping(TEMPLATE_A, CLASS_A, T2);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(
                new MappingAssertion(bMapping, null),
                new MappingAssertion(aMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) aMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("full")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        IQ classA = result.get(MappingAssertionIndex.ofClass(predicate, CLASS_A));
        assertNotNull(classA);
        assertTrue(classA.getTree().getChildren().get(0).getRootNode() instanceof UnionNode,
                "full a:A must merge the B-derived mapping");
    }

    @Test
    public void testExactKeepsPropertySubPropertySaturation() throws Exception {
        OntologyBuilder builder = OntologyBuilderImpl.builder(RDF_FACTORY, TERM_FACTORY);
        ObjectPropertyExpression p = builder.declareObjectProperty(PROP_P);
        ObjectPropertyExpression q = builder.declareObjectProperty(PROP_Q);
        builder.addSubPropertyOfAxiom(p, q);
        Ontology ontology = builder.build();

        IQ pMapping = objectPropertyMapping(TEMPLATE_S, TEMPLATE_O, PROP_P, T1);
        ImmutableList<MappingAssertion> mapping = ImmutableList.of(new MappingAssertion(pMapping, null));

        RDFAtomPredicate predicate = (RDFAtomPredicate) pMapping.getProjectionAtom().getPredicate();

        ImmutableMap<MappingAssertionIndex, IQ> result = createSaturator("exact")
                .saturate(mapping, ontology.tbox()).stream()
                .collect(ImmutableCollectors.toMap(MappingAssertion::getIndex, MappingAssertion::getQuery));

        assertNotNull(result.get(MappingAssertionIndex.ofProperty(predicate, PROP_P)),
                "literal p mapping must be kept");
        assertNotNull(result.get(MappingAssertionIndex.ofProperty(predicate, PROP_Q)),
                "sub-property saturation must remain enabled in exact mode");
    }

    private static MappingSaturator createSaturator(String mode) {
        Properties properties = new Properties();
        properties.setProperty(OntopModelSettings.TYPE_REASONING_MODE, mode);
        Injector injector = OntopMappingConfiguration.defaultBuilder()
                .enableTestMode()
                .properties(properties)
                .build()
                .getInjector();
        return injector.getInstance(MappingSaturator.class);
    }

    private static IQ classMapping(ImmutableList<Template.Component> template, IRI classIri,
                                   NamedRelationDefinition table) {
        DistinctVariableOnlyDataAtom atom = ATOM_FACTORY.getDistinctTripleAtom(S, P, O);
        ConstructionNode root = IQ_FACTORY.createConstructionNode(
                ImmutableSet.of(S, P, O),
                SUBSTITUTION_FACTORY.getSubstitution(
                        S, TERM_FACTORY.getIRIFunctionalTerm(template, ImmutableList.of(X)),
                        P, TERM_FACTORY.getConstantIRI(RDF.TYPE),
                        O, TERM_FACTORY.getConstantIRI(classIri)));
        ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(table, ImmutableMap.of(0, X));
        return IQ_FACTORY.createIQ(atom, IQ_FACTORY.createUnaryIQTree(root, data));
    }

    private static IQ objectPropertyMapping(ImmutableList<Template.Component> subjectTemplate,
                                            ImmutableList<Template.Component> objectTemplate,
                                            IRI propertyIri, NamedRelationDefinition table) {
        DistinctVariableOnlyDataAtom atom = ATOM_FACTORY.getDistinctTripleAtom(S, P, O);
        ConstructionNode root = IQ_FACTORY.createConstructionNode(
                ImmutableSet.of(S, P, O),
                SUBSTITUTION_FACTORY.getSubstitution(
                        S, TERM_FACTORY.getIRIFunctionalTerm(subjectTemplate, ImmutableList.of(X)),
                        P, TERM_FACTORY.getConstantIRI(propertyIri),
                        O, TERM_FACTORY.getIRIFunctionalTerm(objectTemplate, ImmutableList.of(X))));
        ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(table, ImmutableMap.of(0, X));
        return IQ_FACTORY.createIQ(atom, IQ_FACTORY.createUnaryIQTree(root, data));
    }
}
