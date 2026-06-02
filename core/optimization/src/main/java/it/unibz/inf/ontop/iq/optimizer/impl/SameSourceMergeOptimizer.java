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
            LOGGER.info("SameSourceMerge: transformInnerJoin called with {} children", children.size());
            for (int i = 0; i < children.size(); i++) {
                IQTree child = children.get(i);
                LOGGER.info("  child[{}]: type={}", i, child.getClass().getSimpleName());
                if (child instanceof UnaryIQTree) {
                    UnaryIQTree unary = (UnaryIQTree) child;
                    LOGGER.info("  child[{}] rootNode: {}",
                            i, unary.getRootNode().getClass().getSimpleName());
                    LOGGER.info("  child[{}] child: {}",
                            i, unary.getChild().getClass().getSimpleName());
                }
            }

            Optional<IQTree> merged = tryMergeSameSourceNodes(node, children);
            if (merged.isPresent()) {
                LOGGER.info("SameSourceMerge: successfully merged into {}", merged.get().getClass().getSimpleName());
                return merged.get();
            }
            LOGGER.info("SameSourceMerge: merge not applied, transforming children individually");
            ImmutableList<IQTree> transformedChildren = children.stream()
                    .map(c -> c.acceptVisitor(this))
                    .collect(ImmutableCollectors.toList());
            return iqFactory.createNaryIQTree(node, transformedChildren);
        }

        private Optional<IQTree> tryMergeSameSourceNodes(InnerJoinNode joinNode, ImmutableList<IQTree> children) {
            // Phase 1: Strict grouping by (relationDef, keySet) — always safe
            Map<MergeKey, List<MergeCandidate>> groups = new LinkedHashMap<>();

            for (int i = 0; i < children.size(); i++) {
                IQTree child = children.get(i);
                Optional<MergeCandidate> candidate = extractCandidate(child);
                if (!candidate.isPresent()) {
                    LOGGER.info("SameSourceMerge: extractCandidate failed for child[{}]: type={}", i, child.getClass().getSimpleName());
                    return Optional.empty();
                }
                MergeKey key = candidate.get().key();
                LOGGER.info("SameSourceMerge: child[{}] MergeKey: relationDef={}, keySet={}, constrVars={}",
                        i, key.relationDef, key.keySet, candidate.get().constructionNode.getVariables());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate.get());
            }

            // Try strict merging first (same relationDef AND same keySet)
            boolean hasStrictGroup = groups.values().stream().anyMatch(g -> g.size() > 1);

            if (!hasStrictGroup) {
                // Phase 2: Relaxed grouping by relationDef only.
                // We no longer require explicit variable equality condition (e.g., v1.id = v2.id)
                // because:
                // 1. canMergeSafely() already validates that non-common positions are disjoint,
                //    which is the real safety requirement for avoiding column aliasing.
                // 2. When children use the same variable (implicit equality), the join condition
                //    may not contain "=" or "STRICT_EQ2", yet merging is still safe.
                Map<RelationDefinition, List<MergeCandidate>> relaxedGroups = new LinkedHashMap<>();
                for (List<MergeCandidate> group : groups.values()) {
                    for (MergeCandidate c : group) {
                        relaxedGroups.computeIfAbsent(c.extDataNode.getRelationDefinition(),
                                k -> new ArrayList<>()).add(c);
                    }
                }

                boolean hasRelaxedGroup = relaxedGroups.values().stream().anyMatch(g -> g.size() > 1);
                if (!hasRelaxedGroup) {
                    LOGGER.info("SameSourceMerge: no group has more than 1 candidate (strict or relaxed)");
                    return Optional.empty();
                }

                // Use relaxed groups for merging, with safety validation
                Optional<IQTree> result = tryRelaxedMerge(relaxedGroups, joinNode);
                if (!result.isPresent()) {
                    LOGGER.info("SameSourceMerge: relaxed merge rejected (keySet conflicts)");
                    return Optional.empty();
                }
                LOGGER.info("SameSourceMerge: relaxed merge accepted");
                return result;
            }

            // Strict merge (existing behavior)
            LOGGER.info("SameSourceMerge: {} strict groups, attempting merge", groups.size());

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

        /**
         * Checks whether the InnerJoin's filter condition contains variable-to-variable
         * equality (e.g., v1.id = v2.id). Such equalities bind the common key columns
         * across children, making it safe to merge them into a single access.
         *
         * NOTE: this is a heuristic. It returns true if the condition contains "STRICT_EQ2"
         * or " = " which could match variable-to-constant equality. For safety, the caller
         * should rely on canMergeSafely to reject cases where non-common keySet positions
         * have overlapping variables.
         */
        private boolean hasVariableEqualityCondition(InnerJoinNode node) {
            Optional<ImmutableExpression> condition = node.getOptionalFilterCondition();
            if (!condition.isPresent()) return false;

            // Check for variable-to-variable equalities.
            // Ontop represents equality as either "a = b" (infix) or "STRICT_EQ2(a,b)" (function).
            // We check for STRICT_EQ2 as it only appears in function-based equality.
            // The " = " heuristic is more permissive but can be relaxed since
            // canMergeSafely provides the real safety net for keySet conflicts.
            String exprStr = condition.get().toString();
            LOGGER.info("SameSourceMerge: join condition: {}", exprStr);
            return exprStr.contains("STRICT_EQ2") || exprStr.contains(" = ");
        }

        /**
         * Validates whether candidates with the same relationDef but DIFFERENT keySets
         * can be safely merged. Two conditions must hold:
         * 1. Common positions must map to VARIABLES THAT ARE EQUIVALENT via the join condition
         *    (e.g., v1.id = v2.id makes v1.id and v2.id equivalent, so merging is safe).
         *    If they are different variables WITHOUT equality, merging would alias them.
         * 2. Non-common positions must be disjoint across candidates.
         */
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
                            // Different variables at same position - check if they're equivalent
                            if (!areVariablesEquivalent(existingVar, var, equivalenceClasses)) {
                                LOGGER.info("SameSourceMerge: common position {} maps to non-equivalent variables, refusing merge", pos);
                                return false;
                            }
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
         * Handles multiple equality formats:
         * - STRICT_EQ2(A, B)
         * - A = B (infix equality)
         * - CAST(A AS CHAR) = CAST(B AS CHAR) (database-level equality)
         * - AND(STRICT_EQ2(A,B), STRICT_EQ2(C,D)) (conjunction of equalities)
         */
        private Map<Variable, Set<Variable>> buildVariableEquivalenceClasses(InnerJoinNode joinNode) {
            Map<Variable, Set<Variable>> equivalenceClasses = new HashMap<>();
            Optional<ImmutableExpression> condition = joinNode.getOptionalFilterCondition();
            if (!condition.isPresent()) return equivalenceClasses;

            String exprStr = condition.get().toString();
            LOGGER.info("SameSourceMerge: parsing join condition for equalities: {}", exprStr);

            // Pattern 1: STRICT_EQ2(a, b) - function-style equality
            extractEqualityPairs(exprStr, "STRICT_EQ2\\(([^,]+),([^)]+)\\)", equivalenceClasses);

            // Pattern 2: A = B - infix equality (may be nested inside AND, OR, CAST, etc.)
            // This pattern looks for " = " but needs to avoid matching <=, >=, !=
            extractEqualityPairs(exprStr, "([^=<>\\s]+)\\s*=\\s*([^=<>\\s]+)", equivalenceClasses);

            LOGGER.info("SameSourceMerge: found {} equivalence classes", equivalenceClasses.size());
            return equivalenceClasses;
        }

        private void extractEqualityPairs(String exprStr, String regex,
                                          Map<Variable, Set<Variable>> equivalenceClasses) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
            java.util.regex.Matcher matcher = pattern.matcher(exprStr);
            while (matcher.find()) {
                String var1Str = matcher.group(1).trim();
                String var2Str = matcher.group(2).trim();

                // Extract variable names - look for pattern like v1.id or just v1
                String var1 = extractVariableFromEqualitySide(var1Str);
                String var2 = extractVariableFromEqualitySide(var2Str);

                // Skip if either side looks like a constant
                if (var1Str.matches("['\"].*") || var2Str.matches("['\"].*")) continue;
                if (var1Str.matches("\\d+") || var2Str.matches("\\d+")) continue;
                // Skip if we couldn't extract variable names
                if (var1 == null || var2 == null) continue;

                Variable v1 = findVariableByName(var1);
                Variable v2 = findVariableByName(var2);
                if (v1 != null && v2 != null) {
                    equivalenceClasses.computeIfAbsent(v1, k -> new HashSet<>()).add(v2);
                    equivalenceClasses.computeIfAbsent(v2, k -> new HashSet<>()).add(v1);
                    LOGGER.info("SameSourceMerge: added equivalence {} = {}", v1, v2);
                }
            }
        }

        /**
         * Extracts the variable name from one side of an equality expression.
         * Examples:
         * - "v1.id" -> "v1"
         * - "CAST(v1.id AS CHAR)" -> "v1"
         * - "?topic" -> "topic"
         * - "id" -> "id"
         */
        private String extractVariableFromEqualitySide(String expr) {
            // Remove leading ? for SPARQL variables
            if (expr.startsWith("?")) {
                expr = expr.substring(1);
            }

            // Try to find a simple identifier pattern: letter followed by alphanumeric and optional .xxx
            // Pattern: starts with lowercase letter, then alphanumeric or .
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?");
            java.util.regex.Matcher m = p.matcher(expr);
            if (m.find()) {
                return m.group(1); // Return just the first identifier (e.g., "v1" from "v1.id")
            }
            // Fallback: if no pattern matched but it looks simple, return as-is
            if (!expr.contains("(") && !expr.contains("'") && !expr.matches("\\d+")) {
                return expr;
            }
            return null;
        }

        private Variable findVariableByName(String name) {
            // Variables in expression strings look like: ?v0, v0, ?A, etc.
            String varName = name.startsWith("?") ? name.substring(1) : name;
            return termFactory.getVariable(varName);
        }

        private boolean areVariablesEquivalent(VariableOrGroundTerm v1, VariableOrGroundTerm v2,
                                               Map<Variable, Set<Variable>> equivalenceClasses) {
            if (v1.equals(v2)) return true;
            if (!(v1 instanceof Variable) || !(v2 instanceof Variable)) return false;
            Set<Variable> eq1 = equivalenceClasses.get(v1);
            if (eq1 != null && eq1.contains(v2)) return true;
            Set<Variable> eq2 = equivalenceClasses.get(v2);
            if (eq2 != null && eq2.contains(v1)) return true;
            return false;
        }

        /**
         * Attempts a relaxed merge where candidates are grouped by relationDef only.
         * Each group passes through canMergeSafely before merging.
         */
        private Optional<IQTree> tryRelaxedMerge(Map<RelationDefinition, List<MergeCandidate>> relaxedGroups, InnerJoinNode joinNode) {
            List<IQTree> mergedChildren = new ArrayList<>();
            boolean anyMerged = false;

            for (List<MergeCandidate> group : relaxedGroups.values()) {
                if (group.size() > 1 && canMergeSafely(group, joinNode)) {
                    mergedChildren.add(mergeGroup(group));
                    anyMerged = true;
                } else {
                    for (MergeCandidate c : group) {
                        mergedChildren.add(c.tree);
                    }
                }
            }

            if (!anyMerged) return Optional.empty();

            if (mergedChildren.size() == 1) {
                return Optional.of(mergedChildren.get(0));
            }
            return Optional.of(iqFactory.createNaryIQTree(
                    iqFactory.createInnerJoinNode(),
                    ImmutableList.copyOf(mergedChildren)));
        }

        private Optional<MergeCandidate> extractCandidate(IQTree child) {
            LOGGER.info("SameSourceMerge: extractCandidate called for child type: {}", child.getClass().getSimpleName());

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
                        LOGGER.info("SameSourceMerge: grandchild is not ExtData: {}", grandchild.getClass().getSimpleName());
                        return Optional.empty();
                    }
                } else {
                    LOGGER.info("SameSourceMerge: rootNode is not ConstructionNode: {}", unary.getRootNode().getClass().getSimpleName());
                    return Optional.empty();
                }
            } else if (child instanceof ExtensionalDataNode) {
                // ConstructionNode was stripped by InnerJoinNormalizer (empty substitution)
                // Treat as a construction with empty substitution
                extData = (ExtensionalDataNode) child;
                LOGGER.info("SameSourceMerge: child is direct ExtData - creating empty ConstructionNode");
                // Create a dummy ConstructionNode with empty substitution - variables come from extData's argument map
                ImmutableMap<Integer, ? extends VariableOrGroundTerm> args = extData.getArgumentMap();
                ImmutableSet<Variable> vars = args.values().stream()
                        .filter(v -> v instanceof Variable)
                        .map(v -> (Variable) v)
                        .collect(ImmutableCollectors.toSet());
                constr = iqFactory.createConstructionNode(vars, substitutionFactory.getSubstitution());
            } else {
                LOGGER.info("SameSourceMerge: child is neither UnaryIQTree nor ExtData: {}", child.getClass().getSimpleName());
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
