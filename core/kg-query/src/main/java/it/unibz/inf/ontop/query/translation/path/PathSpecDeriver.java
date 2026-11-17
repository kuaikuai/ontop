package it.unibz.inf.ontop.query.translation.path;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.NaryIQTreeTools;
import it.unibz.inf.ontop.iq.node.ConstructionNode;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import it.unibz.inf.ontop.model.atom.AtomPredicate;
import it.unibz.inf.ontop.model.atom.DataAtom;
import it.unibz.inf.ontop.model.atom.RDFAtomPredicate;
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.term.VariableOrGroundTerm;
import it.unibz.inf.ontop.model.term.functionsymbol.db.IRIStringTemplateFunctionSymbol;
import it.unibz.inf.ontop.model.template.Template;
import it.unibz.inf.ontop.model.vocabulary.XSD;
import it.unibz.inf.ontop.spec.mapping.Mapping;
import org.apache.commons.rdf.api.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static it.unibz.inf.ontop.iq.impl.UnaryIQTreeTools.UnaryIQTreeDecomposition;

/**
 * Derives {@link PathSpec.Self} from the mapping definition of an edge predicate.
 *
 * Only handles the simple self-join shape: a ConstructionNode defining subject/object IRI
 * templates with the same prefix, joined over exactly two ExtensionalDataNodes on the same
 * relation sharing exactly one join variable.
 *
 * It also collects, per physical table, the simple attribute mappings (property IRI to column),
 * so that node attributes living in the same table can be carried by the path CTE.
 */
public final class PathSpecDeriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathSpecDeriver.class);

    private PathSpecDeriver() {
    }

    /**
     * @param propertyDatatypes (table, property IRI) -> declared RDF datatype IRI
     *                         (see DefaultMappingTransformer#extractPropertyDatatypes)
     * @param langLiteralProperties (table, property IRI) whose values carry a language tag:
     *                               such properties are excluded from nodeColumns, i.e. they are
     *                               never sunk (they keep their language tag through the join-back)
     */
    public static void deriveAndRegister(Mapping mapping, PathSpecRegistry registry,
                                          Map<String, String> propertyDatatypes, Set<String> langLiteralProperties) {
        Map<String, Map<String, String>> tableColumns = collectTableColumns(mapping, langLiteralProperties);

        for (RDFAtomPredicate predicate : mapping.getRDFAtomPredicates()) {
            for (IRI iri : mapping.getRDFProperties(predicate)) {
                String key = iri.getIRIString();
                if (registry.get(key).isPresent())
                    continue;
                Optional<IQ> iq = mapping.getRDFPropertyDefinition(predicate, iri);
                if (!iq.isPresent()) {
                    LOGGER.warn("[PathSpec] no mapping IQ found for property {}", key);
                    continue;
                }
                DerivationOutcome outcome = derive(key, iq.get(), tableColumns, propertyDatatypes);
                if (outcome.getSpec().isPresent())
                    registry.upsert(outcome.getSpec().get());
                else
                    LOGGER.warn("[PathSpec] cannot derive a path spec for {} : {}", key, outcome.getReason());
            }
        }
    }

    private static Map<String, Map<String, String>> collectTableColumns(Mapping mapping,
                                                                         Set<String> langLiteralProperties) {
        Map<String, Map<String, String>> tableColumns = new HashMap<>();
        for (RDFAtomPredicate predicate : mapping.getRDFAtomPredicates()) {
            for (IRI iri : mapping.getRDFProperties(predicate)) {
                String propertyIri = iri.getIRIString();
                List<PropertyColumn> propertyColumns = mapping.getRDFPropertyDefinition(predicate, iri)
                        .map(PathSpecDeriver::derivePropertyColumns)
                        .orElse(Collections.emptyList());
                for (PropertyColumn propertyColumn : propertyColumns) {
                    if (langLiteralProperties.contains(PathSpec.propertyKey(propertyColumn.table, propertyIri)))
                        continue; // language-tagged: must not be sunk
                    tableColumns.computeIfAbsent(propertyColumn.table, k -> new HashMap<>())
                            .put(propertyIri, propertyColumn.column);
                }
            }
        }
        return tableColumns;
    }

    private static List<PropertyColumn> derivePropertyColumns(IQ iq) {
        DataAtom<AtomPredicate> atom = iq.getProjectionAtom();
        List<? extends VariableOrGroundTerm> args = atom.getArguments();
        if (args.size() < 3 || !(args.get(2) instanceof Variable))
            return Collections.emptyList();
        Variable objectVariable = (Variable) args.get(2);

        IQTree tree = iq.getTree();
        var topConstruction = UnaryIQTreeDecomposition.of(tree, ConstructionNode.class);
        IQTree body = topConstruction.isPresent() ? topConstruction.getChild() : tree;

        // The top-level object may be wrapped (e.g. RDF(VARCHARToTEXT(var))); the Union
        // branches use the underlying variable, so unwrap it before matching columns.
        Variable targetObjectVariable = objectVariable;
        if (topConstruction.isPresent()) {
            Optional<Variable> unwrapped = unwrapColumn(
                    topConstruction.getNode().getSubstitution().apply(objectVariable));
            if (unwrapped.isPresent())
                targetObjectVariable = unwrapped.get();
        }

        var unionDecomposition = NaryIQTreeTools.UnionDecomposition.of(body);
        if (unionDecomposition.isPresent()) {
            List<PropertyColumn> result = new java.util.ArrayList<>();
            for (IQTree child : unionDecomposition.getChildren()) {
                deriveSinglePropertyColumn(child, targetObjectVariable).ifPresent(result::add);
            }
            return result;
        }
        return deriveSinglePropertyColumn(tree, targetObjectVariable)
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());
    }

    private static Optional<PropertyColumn> deriveSinglePropertyColumn(IQTree tree, Variable objectVariable) {
        var constructionDecomposition = UnaryIQTreeDecomposition.of(tree, ConstructionNode.class);
        if (!constructionDecomposition.isPresent())
            return Optional.empty();
        ConstructionNode construction = constructionDecomposition.getNode();
        ExtensionalDataNode source = findExtensional(constructionDecomposition.getChild());
        if (source == null)
            return Optional.empty();

        Optional<Variable> columnVariable = unwrapColumn(construction.getSubstitution().apply(objectVariable));
        if (!columnVariable.isPresent())
            return Optional.empty();
        Integer columnIndex = columnOf(source, columnVariable.get());
        if (columnIndex == null)
            return Optional.empty();

        RelationDefinition relation = source.getRelationDefinition();
        String table = relation.getAtomPredicate().getName();
        String column = relation.getAttribute(columnIndex + 1).getID().getSQLRendering();
        return Optional.of(new PropertyColumn(table, column));
    }

    static DerivationOutcome derive(String predicateIri, IQ iq,
                                    Map<String, Map<String, String>> tableColumns,
                                    Map<String, String> propertyDatatypes) {
        DataAtom<AtomPredicate> atom = iq.getProjectionAtom();
        List<? extends VariableOrGroundTerm> args = atom.getArguments();
        if (args.size() < 3)
            return DerivationOutcome.failure("expected 3 arguments, found " + args.size());
        if (!(args.get(0) instanceof Variable) || !(args.get(2) instanceof Variable))
            return DerivationOutcome.failure("subject/object are not variables");
        Variable subjectVariable = (Variable) args.get(0);
        Variable objectVariable = (Variable) args.get(2);

        IQTree tree = iq.getTree();
        var constructionDecomposition = UnaryIQTreeDecomposition.of(tree, ConstructionNode.class);
        if (!constructionDecomposition.isPresent())
            return DerivationOutcome.failure("the top-level node is not a ConstructionNode (tree: "
                    + describeTree(tree) + ")");
        ConstructionNode construction = constructionDecomposition.getNode();
        IQTree child = constructionDecomposition.getChild();

        var joinChildren = findJoinChildren(child);
        if (!joinChildren.isPresent())
            return DerivationOutcome.failure("no inner join found below the construction node (tree: "
                    + describeTree(tree) + ")");
        ImmutableList<IQTree> children = joinChildren.get();
        if (children.size() != 2)
            return DerivationOutcome.failure("expected exactly 2 join children, found " + children.size()
                    + " (tree: " + describeTree(tree) + ")");
        if (!(children.get(0) instanceof ExtensionalDataNode) || !(children.get(1) instanceof ExtensionalDataNode))
            return DerivationOutcome.failure("the join children are not both extensional nodes (tree: "
                    + describeTree(tree) + ")");
        ExtensionalDataNode first = (ExtensionalDataNode) children.get(0);
        ExtensionalDataNode second = (ExtensionalDataNode) children.get(1);
        if (!first.getRelationDefinition().equals(second.getRelationDefinition()))
            return DerivationOutcome.failure("the two extensional nodes use different relations: "
                    + first.getRelationDefinition().getAtomPredicate().getName() + " vs "
                    + second.getRelationDefinition().getAtomPredicate().getName());

        Optional<IriTemplate> subjectTemplate = extractIriTemplate(
                construction.getSubstitution().apply(subjectVariable));
        Optional<IriTemplate> objectTemplate = extractIriTemplate(
                construction.getSubstitution().apply(objectVariable));
        if (!subjectTemplate.isPresent())
            return DerivationOutcome.failure("the subject IRI is not built from a string template");
        if (!objectTemplate.isPresent())
            return DerivationOutcome.failure("the object IRI is not built from a string template");
        if (!subjectTemplate.get().prefix.equals(objectTemplate.get().prefix))
            return DerivationOutcome.failure("the subject/object IRI prefixes differ: "
                    + subjectTemplate.get().prefix + " vs " + objectTemplate.get().prefix);

        Optional<AliasColumn> subjectAlias = findAlias(first, second, subjectTemplate.get().columnVariable);
        Optional<AliasColumn> objectAlias = findAlias(first, second, objectTemplate.get().columnVariable);
        if (!subjectAlias.isPresent())
            return DerivationOutcome.failure("the subject IRI column was not found in the join children");
        if (!objectAlias.isPresent())
            return DerivationOutcome.failure("the object IRI column was not found in the join children");
        if (subjectAlias.get().node == objectAlias.get().node)
            return DerivationOutcome.failure("the subject and object IRIs come from the same join child");

        Set<Variable> shared = Sets.intersection(variables(first), variables(second));
        if (shared.size() != 1)
            return DerivationOutcome.failure("expected exactly 1 shared join variable, found " + shared.size()
                    + " " + shared);
        Variable joinVariable = shared.iterator().next();

        Integer childJoinIndex = columnOf(subjectAlias.get().node, joinVariable);
        Integer parentJoinIndex = columnOf(objectAlias.get().node, joinVariable);
        if (childJoinIndex == null)
            return DerivationOutcome.failure("the join column was not found on the subject side");
        if (parentJoinIndex == null)
            return DerivationOutcome.failure("the join column was not found on the object side");

        RelationDefinition relation = first.getRelationDefinition();
        String nodeKeyColumn = relation.getAttribute(subjectAlias.get().columnIndex + 1).getID().getSQLRendering();
        String nodeKeyType = relation.getAttribute(subjectAlias.get().columnIndex + 1).getTermType().getName();
        String parentJoinKeyColumn = relation.getAttribute(childJoinIndex + 1).getID().getSQLRendering();
        String joinKeyColumn = relation.getAttribute(parentJoinIndex + 1).getID().getSQLRendering();
        String table = relation.getAtomPredicate().getName();
        Map<String, String> nodeColumns = tableColumns.getOrDefault(table, Collections.emptyMap());

        Map<String, String> nodeColumnTypes = new HashMap<>();
        for (String propertyIri : nodeColumns.keySet()) {
            String datatype = propertyDatatypes.get(PathSpec.propertyKey(table, propertyIri));
            nodeColumnTypes.put(propertyIri,
                    datatype != null ? datatype : XSD.STRING.getIRIString());
        }
        LOGGER.debug("[PathSpec] table {} nodeColumns={} nodeColumnTypes={}", table, nodeColumns, nodeColumnTypes);

        return DerivationOutcome.success(new PathSpec.Self(
                predicateIri,
                table,
                joinKeyColumn,
                parentJoinKeyColumn,
                nodeKeyColumn,
                nodeKeyType,
                subjectTemplate.get().prefix,
                nodeColumns,
                nodeColumnTypes,
                Optional.empty()));
    }

    private static String describeTree(IQTree tree) {
        StringBuilder sb = new StringBuilder();
        IQTree current = tree;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (depth > 0)
                sb.append(" -> ");
            sb.append(current.getClass().getSimpleName());
            if (current instanceof UnaryIQTree) {
                current = ((UnaryIQTree) current).getChild();
            } else if (current instanceof NaryIQTree) {
                NaryIQTree nary = (NaryIQTree) current;
                sb.append('(');
                List<? extends IQTree> children = nary.getChildren();
                for (int i = 0; i < children.size(); i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(children.get(i).getClass().getSimpleName());
                }
                sb.append(')');
                current = null;
            } else {
                current = null;
            }
        }
        return sb.toString();
    }

    private static Optional<ImmutableList<IQTree>> findJoinChildren(IQTree tree) {
        var joinDecomposition = NaryIQTreeTools.InnerJoinDecomposition.of(tree);
        if (joinDecomposition.isPresent())
            return Optional.of(joinDecomposition.getChildren());
        if (tree instanceof UnaryIQTree)
            return findJoinChildren(((UnaryIQTree) tree).getChild());
        return Optional.empty();
    }

    private static ExtensionalDataNode findExtensional(IQTree tree) {
        if (tree instanceof ExtensionalDataNode)
            return (ExtensionalDataNode) tree;
        if (tree instanceof UnaryIQTree)
            return findExtensional(((UnaryIQTree) tree).getChild());
        return null;
    }

    private static Optional<IriTemplate> extractIriTemplate(ImmutableTerm term) {
        if (!(term instanceof ImmutableFunctionalTerm))
            return Optional.empty();
        ImmutableFunctionalTerm rdfTerm = (ImmutableFunctionalTerm) term;
        if (rdfTerm.getTerms().isEmpty())
            return Optional.empty();
        ImmutableTerm lexical = rdfTerm.getTerms().get(0);
        if (!(lexical instanceof ImmutableFunctionalTerm))
            return Optional.empty();
        ImmutableFunctionalTerm lexicalTerm = (ImmutableFunctionalTerm) lexical;
        if (!(lexicalTerm.getFunctionSymbol() instanceof IRIStringTemplateFunctionSymbol))
            return Optional.empty();
        IRIStringTemplateFunctionSymbol templateSymbol =
                (IRIStringTemplateFunctionSymbol) lexicalTerm.getFunctionSymbol();

        StringBuilder prefix = new StringBuilder();
        for (Template.Component component : templateSymbol.getTemplateComponents()) {
            if (!component.isColumn())
                prefix.append(component.getComponent());
        }
        if (prefix.length() == 0)
            return Optional.empty();
        if (lexicalTerm.getTerms().isEmpty())
            return Optional.empty();
        Optional<Variable> columnVariable = unwrapColumn(lexicalTerm.getTerms().get(0));
        return columnVariable.map(v -> new IriTemplate(prefix.toString(), v));
    }

    private static Optional<Variable> unwrapColumn(ImmutableTerm term) {
        ImmutableTerm current = term;
        while (current instanceof ImmutableFunctionalTerm) {
            ImmutableFunctionalTerm functionalTerm = (ImmutableFunctionalTerm) current;
            if (functionalTerm.getTerms().isEmpty())
                return Optional.empty();
            current = functionalTerm.getTerms().get(0);
        }
        return current instanceof Variable ? Optional.of((Variable) current) : Optional.empty();
    }

    private static Optional<AliasColumn> findAlias(ExtensionalDataNode first, ExtensionalDataNode second,
                                                   Variable variable) {
        Integer firstIndex = columnOf(first, variable);
        if (firstIndex != null)
            return Optional.of(new AliasColumn(first, firstIndex));
        Integer secondIndex = columnOf(second, variable);
        if (secondIndex != null)
            return Optional.of(new AliasColumn(second, secondIndex));
        return Optional.empty();
    }

    private static Integer columnOf(ExtensionalDataNode node, Variable variable) {
        return node.getArgumentMap().entrySet().stream()
                .filter(e -> e.getValue().equals(variable))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static Set<Variable> variables(ExtensionalDataNode node) {
        return node.getArgumentMap().values().stream()
                .filter(Variable.class::isInstance)
                .map(Variable.class::cast)
                .collect(Collectors.toSet());
    }

    static final class DerivationOutcome {
        private final PathSpec.Self spec;
        private final String reason;

        private DerivationOutcome(PathSpec.Self spec, String reason) {
            this.spec = spec;
            this.reason = reason;
        }

        static DerivationOutcome success(PathSpec.Self spec) {
            return new DerivationOutcome(spec, null);
        }

        static DerivationOutcome failure(String reason) {
            return new DerivationOutcome(null, reason);
        }

        Optional<PathSpec.Self> getSpec() {
            return Optional.ofNullable(spec);
        }

        String getReason() {
            return reason;
        }
    }

    private static final class IriTemplate {
        private final String prefix;
        private final Variable columnVariable;

        private IriTemplate(String prefix, Variable columnVariable) {
            this.prefix = prefix;
            this.columnVariable = columnVariable;
        }
    }

    private static final class AliasColumn {
        private final ExtensionalDataNode node;
        private final int columnIndex;

        private AliasColumn(ExtensionalDataNode node, int columnIndex) {
            this.node = node;
            this.columnIndex = columnIndex;
        }
    }

    private static final class PropertyColumn {
        private final String table;
        private final String column;

        private PropertyColumn(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }
}
