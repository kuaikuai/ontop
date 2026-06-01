package it.unibz.inf.ontop.iq.optimizer.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.IQTreeTools;
import it.unibz.inf.ontop.iq.node.ConstructionNode;
import it.unibz.inf.ontop.iq.node.ExtensionalDataNode;
import it.unibz.inf.ontop.iq.node.InnerJoinNode;
import it.unibz.inf.ontop.iq.optimizer.IQOptimizer;
import it.unibz.inf.ontop.iq.transform.IQTreeVariableGeneratorTransformer;
import it.unibz.inf.ontop.iq.transform.impl.DefaultRecursiveIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.term.ImmutableExpression;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.term.VariableOrGroundTerm;
import it.unibz.inf.ontop.substitution.Substitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Singleton
public class SameSourceMergeOptimizer extends AbstractIQOptimizer implements IQOptimizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SameSourceMergeOptimizer.class);

    private final IQTreeTools iqTreeTools;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;

    @Inject
    private SameSourceMergeOptimizer(IntermediateQueryFactory iqFactory,
                                     IQTreeTools iqTreeTools,
                                     TermFactory termFactory,
                                     SubstitutionFactory substitutionFactory) {
        super(iqFactory);
        this.iqTreeTools = iqTreeTools;
        this.termFactory = termFactory;
        this.substitutionFactory = substitutionFactory;
    }

    @Override
    protected IQTreeVariableGeneratorTransformer getTransformer() {
        return IQTreeVariableGeneratorTransformer.of(
                vg -> new SameSourceMergeTransformer(vg, iqFactory, iqTreeTools, termFactory, substitutionFactory));
    }

    private static class SameSourceMergeTransformer
            extends DefaultRecursiveIQTreeVisitingTransformer {

        private final IQTreeTools iqTreeTools;
        private final TermFactory termFactory;
        private final SubstitutionFactory substitutionFactory;
        private final VariableGenerator variableGenerator;

        SameSourceMergeTransformer(VariableGenerator vg,
                                   IntermediateQueryFactory iqFactory,
                                   IQTreeTools iqTreeTools,
                                   TermFactory termFactory,
                                   SubstitutionFactory substitutionFactory) {
            super(iqFactory);
            this.iqTreeTools = iqTreeTools;
            this.termFactory = termFactory;
            this.substitutionFactory = substitutionFactory;
            this.variableGenerator = vg;
        }

        @Override
        public IQTree transformInnerJoin(NaryIQTree tree, InnerJoinNode node,
                                          ImmutableList<IQTree> children) {
            Optional<IQTree> merged = tryMergeSameSourceNodes(children);
            if (merged.isPresent()) {
                return merged.get();
            }
            ImmutableList<IQTree> transformedChildren = children.stream()
                    .map(c -> c.acceptVisitor(this))
                    .collect(ImmutableCollectors.toList());
            return iqFactory.createNaryIQTree(node, transformedChildren);
        }

        private Optional<IQTree> tryMergeSameSourceNodes(ImmutableList<IQTree> children) {
            Map<MergeKey, List<MergeCandidate>> groups = new LinkedHashMap<>();

            for (IQTree child : children) {
                Optional<MergeCandidate> candidate = extractCandidate(child);
                if (!candidate.isPresent()) {
                    return Optional.empty();
                }
                MergeKey key = candidate.get().key();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate.get());
            }

            boolean hasMergableGroup = groups.values().stream().anyMatch(g -> g.size() > 1);
            if (!hasMergableGroup) {
                return Optional.empty();
            }

            List<IQTree> mergedChildren = new ArrayList<>();
            for (List<MergeCandidate> group : groups.values()) {
                if (group.size() > 1) {
                    mergedChildren.add(mergeGroup(group));
                } else {
                    mergedChildren.add(group.get(0).tree);
                }
            }

            if (mergedChildren.size() == 1) {
                return Optional.of(mergedChildren.get(0));
            }
            return Optional.of(iqFactory.createNaryIQTree(
                    iqFactory.createInnerJoinNode(),
                    ImmutableList.copyOf(mergedChildren)));
        }

        private Optional<MergeCandidate> extractCandidate(IQTree child) {
            if (!(child instanceof UnaryIQTree)) return Optional.empty();
            UnaryIQTree unary = (UnaryIQTree) child;
            if (!(unary.getRootNode() instanceof ConstructionNode)) return Optional.empty();
            IQTree grandchild = unary.getChild();
            if (!(grandchild instanceof ExtensionalDataNode)) return Optional.empty();

            ConstructionNode constr = (ConstructionNode) unary.getRootNode();
            ExtensionalDataNode extData = (ExtensionalDataNode) grandchild;

            return Optional.of(new MergeCandidate(child, constr, extData));
        }

        private IQTree mergeGroup(List<MergeCandidate> candidates) {
            MergeCandidate first = candidates.get(0);
            ExtensionalDataNode firstExtData = first.extDataNode;
            ImmutableMap<Integer, ? extends VariableOrGroundTerm> mergedArgs = firstExtData.getArgumentMap();

            Substitution<ImmutableTerm> mergedSub = mergeSubstitutions(candidates);

            ExtensionalDataNode mergedExtData = iqFactory.createExtensionalDataNode(
                    firstExtData.getRelationDefinition(), mergedArgs);

            ImmutableSet<Variable> projectedVars = candidates.stream()
                    .flatMap(c -> c.constructionNode.getVariables().stream())
                    .collect(ImmutableCollectors.toSet());
            ConstructionNode mergedConstr = iqFactory.createConstructionNode(projectedVars, mergedSub);

            VariableOrGroundTerm idVar = mergedArgs.get(0);
            ImmutableExpression notNullFilter = termFactory.getDBIsNotNull(
                    (Variable) idVar);

            IQTree constrTree = iqFactory.createUnaryIQTree(mergedConstr, mergedExtData);
            IQTree filterTree = iqFactory.createUnaryIQTree(
                    iqFactory.createFilterNode(notNullFilter), constrTree);

            return filterTree.normalizeForOptimization(variableGenerator);
        }

        private Substitution<ImmutableTerm> mergeSubstitutions(List<MergeCandidate> candidates) {
            MergeCandidate first = candidates.get(0);
            ImmutableMap<Integer, ? extends VariableOrGroundTerm> referenceArgs = first.extDataNode.getArgumentMap();
            Substitution<ImmutableTerm> merged = first.constructionNode.getSubstitution();

            for (int i = 1; i < candidates.size(); i++) {
                MergeCandidate c = candidates.get(i);
                Substitution<ImmutableTerm> sub = c.constructionNode.getSubstitution();
                ImmutableMap<Integer, ? extends VariableOrGroundTerm> candidateArgs = c.extDataNode.getArgumentMap();

                for (Variable v : sub.getDomain()) {
                    ImmutableTerm value = sub.get(v);
                    ImmutableTerm remappedValue = remapToReferenceArgs(value, candidateArgs, referenceArgs);
                    ImmutableTerm existing = merged.get(v);
                    if (existing != null && !existing.equals(remappedValue)) {
                        throw new MinorOntopInternalBugException(
                                "Substitution conflict: variable " + v
                                        + " bound to both " + existing + " and " + remappedValue);
                    }
                    merged = substitutionFactory.union(merged,
                            substitutionFactory.getSubstitution(v, remappedValue));
                }

                ImmutableSet<Variable> candidateChildVars = Sets.difference(
                        c.constructionNode.getVariables(), sub.getDomain()).immutableCopy();
                for (Variable v : candidateChildVars) {
                    ImmutableTerm remappedValue = remapToReferenceArgs(v, candidateArgs, referenceArgs);
                    if (!remappedValue.equals(v)) {
                        ImmutableTerm existing = merged.get(v);
                        if (existing != null && !existing.equals(remappedValue)) {
                            throw new MinorOntopInternalBugException(
                                    "Substitution conflict: variable " + v
                                            + " bound to both " + existing + " and " + remappedValue);
                        }
                        merged = substitutionFactory.union(merged,
                                substitutionFactory.getSubstitution(v, remappedValue));
                    }
                }
            }
            return merged;
        }

        private ImmutableTerm remapToReferenceArgs(ImmutableTerm value,
                                                   ImmutableMap<Integer, ? extends VariableOrGroundTerm> candidateArgs,
                                                   ImmutableMap<Integer, ? extends VariableOrGroundTerm> referenceArgs) {
            if (!(value instanceof Variable)) {
                return value;
            }
            Variable var = (Variable) value;
            for (Map.Entry<Integer, ? extends VariableOrGroundTerm> entry : candidateArgs.entrySet()) {
                if (entry.getValue().equals(var)) {
                    VariableOrGroundTerm refVar = referenceArgs.get(entry.getKey());
                    if (refVar != null) {
                        return refVar;
                    }
                }
            }
            return value;
        }

        private static final class MergeCandidate {
            final IQTree tree;
            final ConstructionNode constructionNode;
            final ExtensionalDataNode extDataNode;

            MergeCandidate(IQTree tree, ConstructionNode constructionNode,
                           ExtensionalDataNode extDataNode) {
                this.tree = tree;
                this.constructionNode = constructionNode;
                this.extDataNode = extDataNode;
            }

            MergeKey key() {
                return new MergeKey(
                        extDataNode.getRelationDefinition(),
                        ImmutableSet.copyOf(extDataNode.getArgumentMap().keySet()));
            }
        }

        private static final class MergeKey {
            final RelationDefinition relationDef;
            final ImmutableSet<Integer> keySet;

            MergeKey(RelationDefinition relationDef, ImmutableSet<Integer> keySet) {
                this.relationDef = relationDef;
                this.keySet = keySet;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof MergeKey)) return false;
                MergeKey that = (MergeKey) o;
                return relationDef.equals(that.relationDef) && keySet.equals(that.keySet);
            }

            @Override
            public int hashCode() {
                return Objects.hash(relationDef, keySet);
            }
        }
    }
}
