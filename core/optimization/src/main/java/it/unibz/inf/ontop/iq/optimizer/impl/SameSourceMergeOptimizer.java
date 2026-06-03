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
import it.unibz.inf.ontop.model.term.ImmutableFunctionalTerm;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.model.term.VariableOrGroundTerm;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBStrictEqFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBTypeConversionFunctionSymbol;
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
        LOGGER.info("SameSourceMergeOptimizer loaded");
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
            LOGGER.debug("SameSourceMerge: transformInnerJoin called with {} children", children.size());
            Optional<IQTree> merged = tryMergeSameSourceNodes(node, children);
            if (merged.isPresent()) {
                LOGGER.info("SameSourceMerge: successfully merged {} children into 1 node", children.size());
                return merged.get();
            }
            ImmutableList<IQTree> transformedChildren = children.stream()
                    .map(c -> c.acceptVisitor(this))
                    .collect(ImmutableCollectors.toList());
            return iqFactory.createNaryIQTree(node, transformedChildren);
        }

        private Optional<IQTree> tryMergeSameSourceNodes(InnerJoinNode joinNode, ImmutableList<IQTree> children) {
            // Separate mergeable candidates from unmergeable children
            List<MergeCandidate> candidates = new ArrayList<>();
            List<IQTree> unmergeableChildren = new ArrayList<>();

            for (int i = 0; i < children.size(); i++) {
                IQTree child = children.get(i);
                Optional<MergeCandidate> candidate = extractCandidate(child);
                if (candidate.isPresent()) {
                    candidates.add(candidate.get());
                    MergeKey key = candidate.get().key();
                    LOGGER.debug("SameSourceMerge: child[{}] MergeKey: relationDef={}, keySet={}, constrVars={}",
                            i, key.relationDef, key.keySet, candidate.get().constructionNode.getVariables());
                } else {
                    LOGGER.debug("SameSourceMerge: extractCandidate failed for child[{}]: type={}",
                            i, child.getClass().getSimpleName());
                    unmergeableChildren.add(child);
                }
            }

            if (candidates.size() <= 1) {
                // Nothing to merge (need at least 2 mergeable candidates)
                return Optional.empty();
            }

            // Phase 1: Group by (relationDef, keySet) for strict merge
            Map<MergeKey, List<MergeCandidate>> strictGroups = new LinkedHashMap<>();
            for (MergeCandidate c : candidates) {
                strictGroups.computeIfAbsent(c.key(), k -> new ArrayList<>()).add(c);
            }

            // Apply strict merge to groups with >1 candidate, collect singletons
            List<IQTree> mergedChildren = new ArrayList<>();
            List<MergeCandidate> remainingSingletons = new ArrayList<>();

            for (List<MergeCandidate> group : strictGroups.values()) {
                if (group.size() > 1) {
                    mergedChildren.add(mergeGroup(group));
                    LOGGER.info("SameSourceMerge: strict merge applied to {} children", group.size());
                } else {
                    remainingSingletons.add(group.get(0));
                }
            }

            // Phase 2: Try relaxed merge for remaining singletons
            if (remainingSingletons.size() > 1) {
                Map<RelationDefinition, List<MergeCandidate>> relaxedGroups = new LinkedHashMap<>();
                for (MergeCandidate c : remainingSingletons) {
                    relaxedGroups.computeIfAbsent(c.extDataNode.getRelationDefinition(),
                            k -> new ArrayList<>()).add(c);
                }

                for (List<MergeCandidate> group : relaxedGroups.values()) {
                    if (group.size() > 1 && canMergeSafely(group, joinNode)) {
                        mergedChildren.add(mergeGroup(group));
                        LOGGER.info("SameSourceMerge: relaxed merge applied to {} children", group.size());
                    } else {
                        for (MergeCandidate c : group) {
                            mergedChildren.add(c.tree);
                        }
                    }
                }
            } else {
                // 0 or 1 singleton: pass through as-is
                for (MergeCandidate c : remainingSingletons) {
                    mergedChildren.add(c.tree);
                }
            }

            // Add unmergeable children back
            mergedChildren.addAll(unmergeableChildren);

            // Collect variable remapping from all merged children
            Substitution<ImmutableTerm> mergedSubstitution = substitutionFactory.getSubstitution();
            for (IQTree child : mergedChildren) {
                if (child instanceof UnaryIQTree
                        && ((UnaryIQTree) child).getRootNode() instanceof ConstructionNode) {
                    Substitution<ImmutableTerm> sub =
                            ((ConstructionNode) ((UnaryIQTree) child).getRootNode()).getSubstitution();
                    mergedSubstitution = substitutionFactory.union(mergedSubstitution, sub);
                }
            }
            final Substitution<ImmutableTerm> finalSubstitution = mergedSubstitution;

            // Rewrite join condition using merged variable remapping.
            // This converts vars eliminated by putIfAbsent (e.g. v2 → v1) so downstream
            // conjuncts like CONTAINS(v2, "x") survive as CONTAINS(v1, "x").
            Optional<ImmutableExpression> rewrittenCondition = joinNode.getOptionalFilterCondition()
                    .flatMap(condition -> {
                        ImmutableTerm result = finalSubstitution.apply(condition);
                        if (result instanceof ImmutableExpression)
                            return Optional.of((ImmutableExpression) result);
                        LOGGER.warn("SameSourceMerge: substitution lost expression type, dropping");
                        return Optional.empty();
                    });

            if (mergedChildren.size() == 1) {
                IQTree result = mergedChildren.get(0);
                if (rewrittenCondition.isPresent()) {
                    ImmutableSet<Variable> mergedVars = result.getVariables();
                    ImmutableList<ImmutableExpression> retained = rewrittenCondition.get().flattenAND()
                            // Drop self-comparisons: v1.val > v1.val after substitution
                            .filter(c -> !isSelfComparison(c))
                            // Only keep conjuncts whose variables still exist
                            .filter(c -> mergedVars.containsAll(extractAllVariables(c)))
                            .collect(ImmutableCollectors.toList());
                    if (!retained.isEmpty()) {
                        LOGGER.debug("SameSourceMerge: retained {} filter conjuncts after merge", retained.size());
                        result = iqFactory.createUnaryIQTree(
                                iqFactory.createFilterNode(termFactory.getConjunction(retained)),
                                result);
                    }
                }
                return Optional.of(result);
            }

            // Only create a new InnerJoin if we actually changed something
            if (mergedChildren.equals(children)) {
                return Optional.empty();
            }

            // Multiple children: keep the rewritten condition
            InnerJoinNode newJoinNode = rewrittenCondition
                    .map(iqFactory::createInnerJoinNode)
                    .orElseGet(iqFactory::createInnerJoinNode);
            return Optional.of(iqFactory.createNaryIQTree(newJoinNode,
                    ImmutableList.copyOf(mergedChildren)));
        }

        private boolean canMergeSafely(List<MergeCandidate> candidates, InnerJoinNode joinNode) {
            if (candidates.size() <= 1) return false;

            // Extract variable equality pairs from join condition (e.g., A = C becomes (A, C))
            Map<Variable, Set<Variable>> equivalenceClasses = buildVariableEquivalenceClasses(joinNode);

            // Find positions that appear in ALL candidates (the "common set")
            Set<Integer> commonPositions = null;
            for (MergeCandidate c : candidates) {
                Set<Integer> posSet = c.extDataNode.getArgumentMap().keySet();
                if (commonPositions == null) {
                    commonPositions = new HashSet<>(posSet);
                } else {
                    commonPositions.retainAll(posSet);
                }
            }

            if (commonPositions == null || commonPositions.isEmpty()) {
                LOGGER.info("SameSourceMerge: no common positions across candidates, refusing to merge");
                return false;
            }

            // Check 1: Common positions must use EQUIVALENT variables in all candidates.
            // If position P is in commonPositions but maps to A in child 0 and C in child 1,
            // merging is safe ONLY IF A = C (via join condition).
            // If they are different variables WITHOUT equality, merging would alias them.
            Map<Integer, VariableOrGroundTerm> positionToVar = new HashMap<>();
            for (MergeCandidate c : candidates) {
                for (Integer pos : commonPositions) {
                    VariableOrGroundTerm var = c.extDataNode.getArgumentMap().get(pos);
                    if (positionToVar.containsKey(pos)) {
                        VariableOrGroundTerm existingVar = positionToVar.get(pos);
                        if (!existingVar.equals(var)) {
                            // Different variables at same position - check if they're equivalent.
                            // If either variable is in the equivalence classes (from join equality conditions),
                            // they must be equivalent. If neither is in any equivalence class, they are
                            // non-key columns (e.g. filter columns like is_delete) and can be safely
                            // resolved by putIfAbsent during merge.
                            boolean existingInEq = existingVar instanceof Variable 
                                    && equivalenceClasses.containsKey((Variable) existingVar);
                            boolean currentInEq = var instanceof Variable 
                                    && equivalenceClasses.containsKey((Variable) var);

                            if (existingInEq || currentInEq) {
                                if (!areVariablesEquivalent(existingVar, var, equivalenceClasses)) {
                                    LOGGER.info("SameSourceMerge: common position {} maps to non-equivalent variables, refusing merge", pos);
                                    return false;
                                }
                            }
                            // else: both variables are non-key (no join equality), skip equivalence check
                        }
                    }
                    positionToVar.put(pos, var);
                }
            }

            // Check 2: Non-common positions must be disjoint across candidates.
            // If position P appears in candidate A and candidate B, and P is NOT
            // in the common set, it means both candidates select the same column position
            // for different purposes — merging would alias them incorrectly.
            Map<Integer, Integer> positionOrigin = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                for (Integer pos : candidates.get(i).extDataNode.getArgumentMap().keySet()) {
                    if (commonPositions.contains(pos)) continue;
                    if (positionOrigin.containsKey(pos)) {
                        LOGGER.info("SameSourceMerge: position {} appears in multiple candidates (non-common), refusing merge", pos);
                        return false;
                    }
                    positionOrigin.put(pos, i);
                }
            }

            return true;
        }

        /**
         * Builds equivalence classes of variables from the join condition.
         * Uses the expression AST (type-safe), not string matching.
         *
         * Steps:
         * 1. Extract direct variable equality pairs from flattenAND() conjuncts
         * 2. Compute transitive closure (connected components in the equality graph)
         *    so that A=C and C=D implies A=D
         *
         * Returns: map from each variable to the FULL set of equivalent variables
         *          (including itself implicitly via areVariablesEquivalent checks)
         */
        private Map<Variable, Set<Variable>> buildVariableEquivalenceClasses(InnerJoinNode joinNode) {
            Map<Variable, Set<Variable>> directPairs = new HashMap<>();
            Optional<ImmutableExpression> condition = joinNode.getOptionalFilterCondition();
            if (!condition.isPresent()) return directPairs;

            // Step 1: Extract direct equality pairs from join condition.
            // Handles both bare-variable equality (v1 = v2) and wrapped equality
            // (CAST(v1.id AS CHAR) = CAST(v2.id AS CHAR)) where variables are
            // nested inside function terms.
            condition.get().flattenAND()
                    .filter(expr -> expr.getFunctionSymbol() instanceof DBStrictEqFunctionSymbol
                            && expr.getTerms().size() == 2)
                    .forEach(expr -> {
                        Optional<Variable> ov1 = extractVariableFromTerm(expr.getTerm(0));
                        Optional<Variable> ov2 = extractVariableFromTerm(expr.getTerm(1));
                        if (ov1.isPresent() && ov2.isPresent()) {
                            Variable v1 = ov1.get();
                            Variable v2 = ov2.get();
                            directPairs.computeIfAbsent(v1, k -> new HashSet<>()).add(v2);
                            directPairs.computeIfAbsent(v2, k -> new HashSet<>()).add(v1);
                        }
                    });

            // Step 2: Compute transitive closure via connected components
            Map<Variable, Set<Variable>> transitiveClosure = new HashMap<>();
            for (Variable v : directPairs.keySet()) {
                if (!transitiveClosure.containsKey(v)) {
                    Set<Variable> component = computeConnectedComponent(v, directPairs);
                    for (Variable member : component) {
                        transitiveClosure.put(member, component);
                    }
                }
            }

            LOGGER.debug("SameSourceMerge: {} direct pairs, {} variables in equivalence classes",
                    directPairs.size(), transitiveClosure.size());
            return transitiveClosure;
        }

        /**
         * Extracts the single underlying variable from a term, unwrapping
         * CAST and other 1-arity functional wrappers (e.g.
         * CAST(v1.id AS CHAR) yields Variable("v1.id")).
         *
         * Returns empty if the term is not reducible to a single variable
         * (e.g. constant, multi-arity function, or no variables).
         */
        private static Optional<Variable> extractVariableFromTerm(ImmutableTerm term) {
            if (term instanceof Variable) {
                return Optional.of((Variable) term);
            }
            if (term instanceof ImmutableFunctionalTerm) {
                ImmutableFunctionalTerm ft = (ImmutableFunctionalTerm) term;

                // Try dedicated uncast for temporary type conversions
                ImmutableTerm unwrapped = DBTypeConversionFunctionSymbol.uncast(term);
                if (unwrapped != term) {
                    return extractVariableFromTerm(unwrapped);
                }

                // For 1-arity functions (like non-temporary CAST),
                // recurse into the single argument
                if (ft.getTerms().size() == 1) {
                    return extractVariableFromTerm(ft.getTerm(0));
                }
            }
            return Optional.empty();
        }

        private Set<Variable> computeConnectedComponent(Variable start,
                                                        Map<Variable, Set<Variable>> directPairs) {
            Set<Variable> visited = new HashSet<>();
            Deque<Variable> stack = new ArrayDeque<>();
            stack.push(start);
            visited.add(start);
            while (!stack.isEmpty()) {
                Variable current = stack.pop();
                Set<Variable> neighbors = directPairs.get(current);
                if (neighbors != null) {
                    for (Variable neighbor : neighbors) {
                        if (visited.add(neighbor)) {
                            stack.push(neighbor);
                        }
                    }
                }
            }
            return visited;
        }

        private boolean areVariablesEquivalent(VariableOrGroundTerm v1, VariableOrGroundTerm v2,
                                               Map<Variable, Set<Variable>> equivalenceClasses) {
            if (v1.equals(v2)) return true;
            if (!(v1 instanceof Variable) || !(v2 instanceof Variable)) return false;
            Set<Variable> eq = equivalenceClasses.get(v1);
            return eq != null && eq.contains(v2);
        }

        /**
         * Returns true for self-comparisons like v1.val > v1.val
         * that can arise after variable substitution during merge.
         */
        private static boolean isSelfComparison(ImmutableExpression expr) {
            return expr.getTerms().size() == 2
                    && expr.getTerm(0).equals(expr.getTerm(1));
        }

        /**
         * Recursively collects all variables from a term tree.
         */
        private static ImmutableSet<Variable> extractAllVariables(ImmutableTerm term) {
            if (term instanceof Variable)
                return ImmutableSet.of((Variable) term);
            if (term instanceof ImmutableFunctionalTerm) {
                ImmutableSet.Builder<Variable> builder = ImmutableSet.builder();
                for (ImmutableTerm subTerm : ((ImmutableFunctionalTerm) term).getTerms())
                    builder.addAll(extractAllVariables(subTerm));
                return builder.build();
            }
            return ImmutableSet.of();
        }

        private Optional<MergeCandidate> extractCandidate(IQTree child) {
            LOGGER.debug("SameSourceMerge: extractCandidate called for child type: {}", child.getClass().getSimpleName());

            ConstructionNode constr;
            ExtensionalDataNode extData;

            if (child instanceof UnaryIQTree) {
                UnaryIQTree unary = (UnaryIQTree) child;
                if (unary.getRootNode() instanceof ConstructionNode) {
                    IQTree grandchild = unary.getChild();
                    if (grandchild instanceof ExtensionalDataNode) {
                        constr = (ConstructionNode) unary.getRootNode();
                        extData = (ExtensionalDataNode) grandchild;
                    } else {
                        LOGGER.debug("SameSourceMerge: grandchild is not ExtData: {}", grandchild.getClass().getSimpleName());
                        return Optional.empty();
                    }
                } else {
                    LOGGER.debug("SameSourceMerge: rootNode is not ConstructionNode: {}", unary.getRootNode().getClass().getSimpleName());
                    return Optional.empty();
                }
            } else if (child instanceof ExtensionalDataNode) {
                // ConstructionNode was stripped by InnerJoinNormalizer (empty substitution)
                // Treat as a construction with empty substitution
                extData = (ExtensionalDataNode) child;
                LOGGER.debug("SameSourceMerge: child is direct ExtData - creating empty ConstructionNode");
                // Create a dummy ConstructionNode with empty substitution - variables come from extData's argument map
                ImmutableMap<Integer, ? extends VariableOrGroundTerm> args = extData.getArgumentMap();
                ImmutableSet<Variable> vars = args.values().stream()
                        .filter(v -> v instanceof Variable)
                        .map(v -> (Variable) v)
                        .collect(ImmutableCollectors.toSet());
                constr = iqFactory.createConstructionNode(vars, substitutionFactory.getSubstitution());
            } else {
                LOGGER.debug("SameSourceMerge: child is neither UnaryIQTree nor ExtData: {}", child.getClass().getSimpleName());
                return Optional.empty();
            }

            return Optional.of(new MergeCandidate(child, constr, extData));
        }

        private IQTree mergeGroup(List<MergeCandidate> candidates) {
            MergeCandidate first = candidates.get(0);

            // Build merged argument map: first-seen variable for each position.
            // Position 0 (id) is shared by all children - take the first child's variable.
            // Other positions (1, 2, 4, 6, 7, 8, 9) appear in different children
            // and are captured by putIfAbsent.
            Map<Integer, VariableOrGroundTerm> mergedArgsMap = new LinkedHashMap<>();
            for (MergeCandidate c : candidates) {
                for (Map.Entry<Integer, ? extends VariableOrGroundTerm> entry
                        : c.extDataNode.getArgumentMap().entrySet()) {
                    mergedArgsMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            ImmutableMap<Integer, VariableOrGroundTerm> mergedArgs = ImmutableMap.copyOf(mergedArgsMap);

            // Use the MERGED args as reference so remapToReferenceArgs can resolve
            // positions that exist in later children but not in the first child
            Substitution<ImmutableTerm> mergedSub = mergeSubstitutions(candidates, mergedArgs);

            ExtensionalDataNode mergedExtData = iqFactory.createExtensionalDataNode(
                    first.extDataNode.getRelationDefinition(), mergedArgs);

            // Projection: all mergedExtData argument variables (pass-through)
            // plus variables introduced by the substitution (aliases)
            ImmutableSet.Builder<Variable> projectedBuilder = ImmutableSet.builder();
            for (VariableOrGroundTerm v : mergedArgs.values()) {
                if (v instanceof Variable) {
                    projectedBuilder.add((Variable) v);
                }
            }
            projectedBuilder.addAll(mergedSub.getDomain());
            ConstructionNode mergedConstr = iqFactory.createConstructionNode(projectedBuilder.build(), mergedSub);

            return iqFactory.createUnaryIQTree(mergedConstr, mergedExtData);
        }

        private Substitution<ImmutableTerm> mergeSubstitutions(List<MergeCandidate> candidates) {
            return mergeSubstitutions(candidates,
                    ImmutableMap.copyOf(candidates.get(0).extDataNode.getArgumentMap()));
        }

        private Substitution<ImmutableTerm> mergeSubstitutions(List<MergeCandidate> candidates,
                                                                ImmutableMap<Integer, ? extends VariableOrGroundTerm> referenceArgs) {
            MergeCandidate first = candidates.get(0);
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
