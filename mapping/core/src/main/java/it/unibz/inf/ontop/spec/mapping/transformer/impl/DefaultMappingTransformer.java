package it.unibz.inf.ontop.spec.mapping.transformer.impl;

import com.google.common.collect.*;
import com.google.inject.Inject;
import it.unibz.inf.ontop.dbschema.DBParameters;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.injection.OntopMappingSettings;
import it.unibz.inf.ontop.injection.SpecificationFactory;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.IQTreeTools;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import it.unibz.inf.ontop.iq.optimizer.DisjunctionOfEqualitiesMergingSimplifier;
import it.unibz.inf.ontop.iq.tools.UnionBasedQueryMerger;
import it.unibz.inf.ontop.model.atom.RDFAtomPredicate;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.RDFConstant;
import it.unibz.inf.ontop.model.term.RDFTermTypeConstant;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.term.VariableOrGroundTerm;
import it.unibz.inf.ontop.model.type.RDFDatatype;
import it.unibz.inf.ontop.model.type.RDFTermType;
import it.unibz.inf.ontop.spec.mapping.*;
import it.unibz.inf.ontop.spec.mapping.impl.MappingImpl;
import it.unibz.inf.ontop.spec.ontology.Ontology;
import it.unibz.inf.ontop.spec.OBDASpecification;
import it.unibz.inf.ontop.spec.mapping.transformer.*;
import it.unibz.inf.ontop.spec.ontology.RDFFact;
import it.unibz.inf.ontop.spec.ontology.impl.OntologyBuilderImpl;
import it.unibz.inf.ontop.spec.rule.RuleExecutor;
import it.unibz.inf.ontop.substitution.Substitution;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

import static it.unibz.inf.ontop.injection.OntopModelSettings.CardinalityPreservationMode.LOOSE;

public class DefaultMappingTransformer implements MappingTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMappingTransformer.class);

    private final MappingVariableNameNormalizer mappingVariableNameNormalizer;
    private final MappingSaturator mappingSaturator;
    private final FactIntoMappingConverter factConverter;
    private final OntopMappingSettings settings;
    private final MappingSameAsInverseRewriter sameAsInverseRewriter;
    private final SpecificationFactory specificationFactory;
    private final RDF rdfFactory;

    private final MappingDistinctTransformer mappingDistinctTransformer;
    private final MappingValuesWrapper mappingValuesWrapper;

    private final TermFactory termFactory;
    private final RuleExecutor ruleExecutor;

    private final DisjunctionOfEqualitiesMergingSimplifier disjunctionOfEqualitiesMergingSimplifier;

    private final UnionBasedQueryMerger queryMerger;
    private final IntermediateQueryFactory iqFactory;
    private final IQTreeTools iqTreeTools;

    @Inject
    private DefaultMappingTransformer(MappingVariableNameNormalizer mappingVariableNameNormalizer,
                                      MappingSaturator mappingSaturator,
                                      FactIntoMappingConverter inserter,
                                      OntopMappingSettings settings,
                                      MappingSameAsInverseRewriter sameAsInverseRewriter,
                                      SpecificationFactory specificationFactory,
                                      RDF rdfFactory,
                                      MappingDistinctTransformer mappingDistinctTransformer,
                                      MappingValuesWrapper mappingValuesWrapper,
                                      DisjunctionOfEqualitiesMergingSimplifier disjunctionOfEqualitiesMergingSimplifier,
                                      TermFactory termFactory,
                                      RuleExecutor ruleExecutor,
                                      UnionBasedQueryMerger queryMerger,
                                      IntermediateQueryFactory iqFactory, IQTreeTools iqTreeTools) {
        this.mappingVariableNameNormalizer = mappingVariableNameNormalizer;
        this.mappingSaturator = mappingSaturator;
        this.factConverter = inserter;
        this.settings = settings;
        this.sameAsInverseRewriter = sameAsInverseRewriter;
        this.specificationFactory = specificationFactory;
        this.rdfFactory = rdfFactory;
        this.mappingDistinctTransformer = mappingDistinctTransformer;
        this.mappingValuesWrapper = mappingValuesWrapper;
        this.disjunctionOfEqualitiesMergingSimplifier = disjunctionOfEqualitiesMergingSimplifier;
        this.termFactory = termFactory;
        this.ruleExecutor = ruleExecutor;
        this.queryMerger = queryMerger;
        this.iqFactory = iqFactory;
        this.iqTreeTools = iqTreeTools;
    }

    @Override
    public OBDASpecification transform(ImmutableList<MappingAssertion> mapping, DBParameters dbParameters,
                                       Optional<Ontology> optionalOntology, ImmutableSet<RDFFact> facts,
                                       ImmutableList<IQ> rules) {

        /*
         * Datatype extraction must happen before saturation (the RDF(lexical, <datatype>)
         * wrapper of the object definition is optimized away by the saturator) and only
         * depends on the mapping assertions, so it is done first. See
         * docs/superpowers/specs/2026-11-17-property-datatype-for-path-sinking-design.md (§4.2)
         */
        PropertyDatatypeExtraction propertyDatatypes = extractPropertyDatatypes(mapping);

        ImmutableList<MappingAssertion> factsAsMapping = factConverter.convert(facts);
        ImmutableList<MappingAssertion> mappingWithFacts =
                Stream.concat(mapping.stream(), factsAsMapping.stream()).collect(ImmutableCollectors.toList());

        Ontology ontology = optionalOntology.orElseGet(() -> OntologyBuilderImpl.builder(rdfFactory, termFactory).build());

        ImmutableList<MappingAssertion> sameAsRewrittenMapping = sameAsInverseRewriter.rewrite(mappingWithFacts);
        ImmutableList<MappingAssertion> saturatedMapping = mappingSaturator.saturate(sameAsRewrittenMapping, ontology.tbox());

        ImmutableList<MappingAssertion> simplifiedBooleanExpressionsMapping = saturatedMapping.stream()
                .map(m -> m.copyOf(disjunctionOfEqualitiesMergingSimplifier.optimize(m.getQuery())))
                .collect(ImmutableCollectors.toList());

        ImmutableList<MappingAssertion> mappingAfterApplyingRules = ruleExecutor.apply(simplifiedBooleanExpressionsMapping, rules);
        ImmutableList<MappingAssertion> mappingWithNormalizedVarNames = mappingVariableNameNormalizer.normalize(mappingAfterApplyingRules);

        // Don't insert the distinct if the cardinality preservation is set to LOOSE
        ImmutableList<MappingAssertion> mappingWithRightCardinality = settings.getCardinalityPreservationMode() == LOOSE
                ? mappingWithNormalizedVarNames
                : mappingDistinctTransformer.addDistinct(mappingWithNormalizedVarNames);

        ImmutableList<MappingAssertion> finalMapping = mappingValuesWrapper.normalize(mappingWithRightCardinality, dbParameters);

        return specificationFactory.createSpecification(getMapping(finalMapping), dbParameters, ontology.tbox(),
                propertyDatatypes.getPropertyDatatypes(), propertyDatatypes.getLangLiteralProperties());
    }

    /**
     * Extracts, for each datatype property of the (unsaturated) mapping, the RDF datatype
     * declared by its object definition (as filled by the MappingDatatypeFiller), keyed by
     * (table, property IRI). Properties whose values carry a language tag are collected
     * separately: they must not be sunk into the path CTE (see §4.2 of the design).
     */
    private static PropertyDatatypeExtraction extractPropertyDatatypes(ImmutableList<MappingAssertion> assertions) {
        Map<String, String> datatypes = new HashMap<>();
        Set<String> langProperties = new HashSet<>();

        for (MappingAssertion assertion : assertions) {
            if (assertion.getIndex().isClass())
                continue;
            RDFAtomPredicate predicate = assertion.getRDFAtomPredicate();
            /*
             * The predicate argument of the (unsaturated) atom is a variable: the property IRI
             * is only available from the assertion index (empty for meta-mappings, which are
             * skipped: the definition of the predicate is not always a ground term there).
             */
            IRI propertyIRI;
            try {
                propertyIRI = assertion.getIndex().getIri();
            } catch (MappingAssertion.NoGroundPredicateOntopInternalBugException e) {
                continue;
            }
            ImmutableList<? extends VariableOrGroundTerm> args = assertion.getProjectionAtom().getArguments();
            VariableOrGroundTerm object = predicate.getObject(args);
            if (!(object instanceof Variable))
                continue;

            for (ImmutableTerm definition : objectDefinitions((Variable) object, assertion)) {
                Optional<RDFDatatype> datatype = findDatatype(definition);
                if (!datatype.isPresent())
                    continue;
                Optional<String> table = extractTable(definition, assertion);
                if (!table.isPresent()) {
                    LOGGER.warn("[PropertyDatatypes] Cannot determine the table of property {}; skipping it",
                            propertyIRI);
                    continue;
                }

                String key = propertyKey(table.get(), propertyIRI.getIRIString());
                if (datatype.get().getLanguageTag().isPresent()) {
                    // Language-tagged literal: must not be sunk (keeps its tag through the join-back)
                    langProperties.add(key);
                    continue;
                }
                String datatypeIri = datatype.get().getIRI().getIRIString();
                String previous = datatypes.get(key);
                if (previous != null && !previous.equals(datatypeIri)) {
                    LOGGER.warn("[PropertyDatatypes] Datatype conflict for property {} on table {}: {} vs {}; keeping the first",
                            propertyIRI, table.get(), previous, datatypeIri);
                    continue;
                }
                datatypes.putIfAbsent(key, datatypeIri);
            }
        }
        LOGGER.debug("[PropertyDatatypes] extracted datatypes={} langProperties={}", datatypes, langProperties);
        return new PropertyDatatypeExtraction(ImmutableMap.copyOf(datatypes), ImmutableSet.copyOf(langProperties));
    }

    private static List<ImmutableTerm> objectDefinitions(Variable object, MappingAssertion assertion) {
        List<ImmutableTerm> definitions = new ArrayList<>();
        for (Substitution<? extends ImmutableTerm> substitution : assertion.getQuery().getTree().getPossibleVariableDefinitions()) {
            ImmutableTerm definition = substitution.get(object);
            if (definition != null)
                definitions.add(definition);
        }
        return definitions;
    }

    /**
     * Finds the RDF datatype carried by the object definition, which may be wrapped
     * (e.g. IF_ELSE_NULL), a constant literal, or an IRI-typed term (no datatype).
     */
    private static Optional<RDFDatatype> findDatatype(ImmutableTerm term) {
        if (term instanceof RDFConstant) {
            RDFTermType type = ((RDFConstant) term).getType();
            return type instanceof RDFDatatype ? Optional.of((RDFDatatype) type) : Optional.empty();
        }
        if (term instanceof RDFTermTypeConstant) {
            RDFTermType type = ((RDFTermTypeConstant) term).getRDFTermType();
            return type instanceof RDFDatatype ? Optional.of((RDFDatatype) type) : Optional.empty();
        }
        if (term instanceof ImmutableFunctionalTerm) {
            for (ImmutableTerm subTerm : ((ImmutableFunctionalTerm) term).getTerms()) {
                Optional<RDFDatatype> found = findDatatype(subTerm);
                if (found.isPresent())
                    return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Determines the physical table defining the lexical column of the object definition.
     * Mirrors the column resolution of PathSpecDeriver (a parallel implementation on purpose:
     * the module dependency direction does not allow mapping/core to depend on core/kg-query).
     */
    private static Optional<String> extractTable(ImmutableTerm definition, MappingAssertion assertion) {
        Optional<Variable> lexicalVariable = unwrapLexicalVariable(definition);
        if (!lexicalVariable.isPresent())
            return Optional.empty();
        Set<String> tables = new LinkedHashSet<>();
        collectTableNames(assertion.getQuery().getTree(), lexicalVariable.get(), tables);
        return tables.size() == 1
                ? Optional.of(tables.iterator().next())
                : Optional.empty();
    }

    private static Optional<Variable> unwrapLexicalVariable(ImmutableTerm term) {
        ImmutableTerm current = term;
        while (current instanceof ImmutableFunctionalTerm) {
            ImmutableFunctionalTerm functionalTerm = (ImmutableFunctionalTerm) current;
            if (functionalTerm.getTerms().isEmpty())
                return Optional.empty();
            current = functionalTerm.getTerms().get(0);
        }
        return current instanceof Variable ? Optional.of((Variable) current) : Optional.empty();
    }

    private static void collectTableNames(IQTree tree, Variable lexicalVariable, Set<String> tables) {
        if (tree instanceof ExtensionalDataNode) {
            ExtensionalDataNode node = (ExtensionalDataNode) tree;
            if (node.getArgumentMap().containsValue(lexicalVariable))
                tables.add(node.getRelationDefinition().getAtomPredicate().getName());
            return;
        }
        if (tree instanceof UnaryIQTree) {
            collectTableNames(((UnaryIQTree) tree).getChild(), lexicalVariable, tables);
            return;
        }
        if (tree instanceof NaryIQTree) {
            for (IQTree child : ((NaryIQTree) tree).getChildren())
                collectTableNames(child, lexicalVariable, tables);
        }
    }

    /** (table, property IRI) key, shared format with PathSpecDeriver. */
    private static String propertyKey(String table, String propertyIri) {
        return table + "\u0000" + propertyIri;
    }

    private static final class PropertyDatatypeExtraction {
        private final ImmutableMap<String, String> propertyDatatypes;
        private final ImmutableSet<String> langLiteralProperties;

        private PropertyDatatypeExtraction(ImmutableMap<String, String> propertyDatatypes,
                                          ImmutableSet<String> langLiteralProperties) {
            this.propertyDatatypes = propertyDatatypes;
            this.langLiteralProperties = langLiteralProperties;
        }

        private Map<String, String> getPropertyDatatypes() {
            return propertyDatatypes;
        }

        private Set<String> getLangLiteralProperties() {
            return langLiteralProperties;
        }
    }

    private Mapping getMapping(ImmutableList<MappingAssertion> assertions) {
        ImmutableTable<RDFAtomPredicate, IRI, IQ> propertyDefinitions = assertions.stream()
                .filter(e -> !e.getIndex().isClass())
                .map(DefaultMappingTransformer::asCell)
                .collect(ImmutableCollectors.toTable());

        ImmutableTable<RDFAtomPredicate, IRI, IQ> classDefinitions = assertions.stream()
                .filter(e -> e.getIndex().isClass())
                .map(DefaultMappingTransformer::asCell)
                .collect(ImmutableCollectors.toTable());


        return new MappingImpl(propertyDefinitions, classDefinitions, termFactory, queryMerger, iqFactory, iqTreeTools);
    }

    private static Table.Cell<RDFAtomPredicate, IRI, IQ> asCell(MappingAssertion assertion) {
        MappingAssertionIndex index = assertion.getIndex();
        return Tables.immutableCell(index.getPredicate(), index.getIri(), assertion.getQuery());
    }
}
