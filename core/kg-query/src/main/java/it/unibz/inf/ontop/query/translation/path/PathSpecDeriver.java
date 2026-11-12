package it.unibz.inf.ontop.query.translation.path;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import it.unibz.inf.ontop.dbschema.PathSpec;
import it.unibz.inf.ontop.dbschema.PathSpecRegistry;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.IQTree;
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
import it.unibz.inf.ontop.spec.mapping.Mapping;
import org.apache.commons.rdf.api.IRI;

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

    private PathSpecDeriver() {
    }

    public static void deriveAndRegister(Mapping mapping, PathSpecRegistry registry) {
        Map<String, Map<String, String>> tableColumns = collectTableColumns(mapping);

        for (RDFAtomPredicate predicate : mapping.getRDFAtomPredicates()) {
            for (IRI iri : mapping.getRDFProperties(predicate)) {
                String key = iri.getIRIString();
                if (registry.get(key).isPresent())
                    continue;
                mapping.getRDFPropertyDefinition(predicate, iri)
                        .flatMap(iq -> derive(key, iq, tableColumns))
                        .ifPresent(registry::upsert);
            }
        }
    }

    private static Map<String, Map<String, String>> collectTableColumns(Mapping mapping) {
        Map<String, Map<String, String>> tableColumns = new HashMap<>();
        for (RDFAtomPredicate predicate : mapping.getRDFAtomPredicates()) {
            for (IRI iri : mapping.getRDFProperties(predicate)) {
                mapping.getRDFPropertyDefinition(predicate, iri)
                        .flatMap(PathSpecDeriver::derivePropertyColumn)
                        .ifPresent(propertyColumn -> tableColumns
                                .computeIfAbsent(propertyColumn.table, k -> new HashMap<>())
                                .put(iri.getIRIString(), propertyColumn.column));
            }
        }
        return tableColumns;
    }

    private static Optional<PropertyColumn> derivePropertyColumn(IQ iq) {
        DataAtom<AtomPredicate> atom = iq.getProjectionAtom();
        List<? extends VariableOrGroundTerm> args = atom.getArguments();
        if (args.size() < 3 || !(args.get(2) instanceof Variable))
            return Optional.empty();
        Variable objectVariable = (Variable) args.get(2);

        var constructionDecomposition = UnaryIQTreeDecomposition.of(iq.getTree(), ConstructionNode.class);
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

    static Optional<PathSpec.Self> derive(String predicateIri, IQ iq,
                                          Map<String, Map<String, String>> tableColumns) {
        DataAtom<AtomPredicate> atom = iq.getProjectionAtom();
        List<? extends VariableOrGroundTerm> args = atom.getArguments();
        if (args.size() < 3)
            return Optional.empty();
        if (!(args.get(0) instanceof Variable) || !(args.get(2) instanceof Variable))
            return Optional.empty();
        Variable subjectVariable = (Variable) args.get(0);
        Variable objectVariable = (Variable) args.get(2);

        IQTree tree = iq.getTree();
        var constructionDecomposition = UnaryIQTreeDecomposition.of(tree, ConstructionNode.class);
        if (!constructionDecomposition.isPresent())
            return Optional.empty();
        ConstructionNode construction = constructionDecomposition.getNode();
        IQTree child = constructionDecomposition.getChild();

        var joinDecomposition = NaryIQTreeTools.InnerJoinDecomposition.of(child);
        if (!joinDecomposition.isPresent())
            return Optional.empty();
        ImmutableList<IQTree> children = joinDecomposition.getChildren();
        if (children.size() != 2)
            return Optional.empty();
        if (!(children.get(0) instanceof ExtensionalDataNode) || !(children.get(1) instanceof ExtensionalDataNode))
            return Optional.empty();
        ExtensionalDataNode first = (ExtensionalDataNode) children.get(0);
        ExtensionalDataNode second = (ExtensionalDataNode) children.get(1);
        if (!first.getRelationDefinition().equals(second.getRelationDefinition()))
            return Optional.empty();

        Optional<IriTemplate> subjectTemplate = extractIriTemplate(
                construction.getSubstitution().apply(subjectVariable));
        Optional<IriTemplate> objectTemplate = extractIriTemplate(
                construction.getSubstitution().apply(objectVariable));
        if (!subjectTemplate.isPresent() || !objectTemplate.isPresent())
            return Optional.empty();
        if (!subjectTemplate.get().prefix.equals(objectTemplate.get().prefix))
            return Optional.empty();

        Optional<AliasColumn> subjectAlias = findAlias(first, second, subjectTemplate.get().columnVariable);
        Optional<AliasColumn> objectAlias = findAlias(first, second, objectTemplate.get().columnVariable);
        if (!subjectAlias.isPresent() || !objectAlias.isPresent())
            return Optional.empty();
        if (subjectAlias.get().node == objectAlias.get().node)
            return Optional.empty();

        Set<Variable> shared = Sets.intersection(variables(first), variables(second));
        if (shared.size() != 1)
            return Optional.empty();
        Variable joinVariable = shared.iterator().next();

        Integer childJoinIndex = columnOf(subjectAlias.get().node, joinVariable);
        Integer parentJoinIndex = columnOf(objectAlias.get().node, joinVariable);
        if (childJoinIndex == null || parentJoinIndex == null)
            return Optional.empty();

        RelationDefinition relation = first.getRelationDefinition();
        String nodeKeyColumn = relation.getAttribute(subjectAlias.get().columnIndex + 1).getID().getSQLRendering();
        String nodeKeyType = relation.getAttribute(subjectAlias.get().columnIndex + 1).getTermType().getName();
        String parentJoinKeyColumn = relation.getAttribute(childJoinIndex + 1).getID().getSQLRendering();
        String joinKeyColumn = relation.getAttribute(parentJoinIndex + 1).getID().getSQLRendering();
        String table = relation.getAtomPredicate().getName();
        Map<String, String> nodeColumns = tableColumns.getOrDefault(table, Collections.emptyMap());

        return Optional.of(new PathSpec.Self(
                predicateIri,
                table,
                joinKeyColumn,
                parentJoinKeyColumn,
                nodeKeyColumn,
                nodeKeyType,
                subjectTemplate.get().prefix,
                nodeColumns,
                Optional.empty()));
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
