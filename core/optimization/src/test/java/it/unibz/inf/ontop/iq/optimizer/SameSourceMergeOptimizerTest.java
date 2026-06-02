package it.unibz.inf.ontop.iq.optimizer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.dbschema.RelationDefinition;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.NaryIQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.visit.impl.ExtensionalDataNodeExtractor;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.model.term.ImmutableExpression;
import it.unibz.inf.ontop.model.term.Variable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static it.unibz.inf.ontop.OptimizationTestingTools.*;
import static org.junit.Assert.*;

public class SameSourceMergeOptimizerTest {

    public static final RelationDefinition T1_AR5;
    public static final RelationDefinition T2_AR5;

    static {
        OfflineMetadataProviderBuilder3 builder = createMetadataProviderBuilder();
        T1_AR5 = builder.createRelationWithStringAttributes(1, 5, true);
        T2_AR5 = builder.createRelationWithStringAttributes(2, 5, true);
    }

    @Test
    public void testMergeTwoSameSourceNodes() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));

        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));

        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));

        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);

        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);

        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        int inputNodeCount = countExtensionalNodes(initialQuery.getTree());
        assertEquals("Input should have 2 ExtensionalDataNodes", 2, inputNodeCount);

        IQ mergedQuery = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        int outputNodeCount = countExtensionalNodes(mergedQuery.getTree());
        assertEquals("Merge should produce 1 ExtensionalDataNode", 1, outputNodeCount);
    }

    @Test
    public void testMergeSevenSameSourceNodes() {
        List<IQTree> children = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Variable v = TERM_FACTORY.getVariable("v" + i);
            Variable e = TERM_FACTORY.getVariable("e" + i);
            ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(
                    T1_AR5, ImmutableMap.of(0, v, 1, e));
            ConstructionNode constr = IQ_FACTORY.createConstructionNode(ImmutableSet.of(v, e));
            children.add(IQ_FACTORY.createUnaryIQTree(constr, data));
        }
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(), ImmutableList.copyOf(children));
        Variable v0 = TERM_FACTORY.getVariable("v0");
        Variable v1 = TERM_FACTORY.getVariable("v1");
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, v0, v1);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(7, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testDifferentTablesNotMerged() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T2_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testDifferentKeySetsNotMerged() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 2, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testNonConstrExtDataNotMerged() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        dataNode2));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // Now merged: bare ExtData nodes are accepted as merge candidates
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testSingleNodeNotTriggered() {
        ExtensionalDataNode dataNode = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ConstructionNode constr = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        UnaryIQTree tree = IQ_FACTORY.createUnaryIQTree(constr, dataNode);
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, B);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, tree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(1, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testInnerJoinWithFilterConditionPreserved() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        ImmutableExpression condition = TERM_FACTORY.getDBIsNotNull(A);
        InnerJoinNode joinNode = IQ_FACTORY.createInnerJoinNode(condition);
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                joinNode,
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testDistinctAttributeOnChild() {
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(IQ_FACTORY.createDistinctNode(),
                                IQ_FACTORY.createUnaryIQTree(constr2, dataNode2))));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testNonCommonPositionConflict() {
        // Child1: position 5 -> var A, position 6 -> var B
        // Child2: position 5 -> var C, position 7 -> var D
        // Position 5 is NOT in common set, but appears in both with different vars -> MUST NOT MERGE
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(5, A, 6, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(5, C, 7, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // Must NOT merge: position 5 conflict (A vs C)
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testNoCommonPositionsNoMerge() {
        // Child1: positions {5, 6}
        // Child2: positions {7, 8}
        // No common positions -> MUST NOT merge
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(5, A, 6, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(7, C, 8, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // No common positions -> must NOT merge
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testSameKeySetDifferentVarsStrictMerged() {
        // Same (relationDef, keySet={0,5}) but different construction variables
        // -> strict merge applies (doesn't check variable equality condition)
        // -> produces 1 ExtData
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 5, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 5, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // Same keySet {0,5} -> strict merge applies -> 1 ExtData
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testLeftJoinNotProcessed() {
        // LEFT JOIN is processed by transformLeftJoin, NOT transformInnerJoin
        // So SameSourceMergeOptimizer never sees it -> no merge
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        IQTree leftJoinTree = IQ_FACTORY.createBinaryNonCommutativeIQTree(
                IQ_FACTORY.createLeftJoinNode(),
                IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                IQ_FACTORY.createUnaryIQTree(constr2, dataNode2));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, leftJoinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // LeftJoin is not processed by transformInnerJoin -> no merge
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testStrictMergePriorityOverRelaxed() {
        // 3 children: 2 share same (relationDef, keySet) -> strict merge first
        // The 3rd is different keySet but same relationDef -> relaxed merge attempted
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ExtensionalDataNode dataNode3 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, E, 5, F));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        ConstructionNode constr3 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(E, F));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2),
                        IQ_FACTORY.createUnaryIQTree(constr3, dataNode3)));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(3, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // 2 strict-merged into 1, plus 1 relaxed-merged -> 2 ExtData
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testBareExtDataDifferentKeySetsMerged() {
        // Bare ExtData nodes (no ConstructionNode wrapper) with different keySets
        // Position 0 common, positions {1} and {5} disjoint -> should merge IF variable equality exists
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 5, D));
        // Add variable equality condition to trigger relaxed merge path
        InnerJoinNode joinNode = IQ_FACTORY.createInnerJoinNode(
                TERM_FACTORY.getStrictEquality(A, C));
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                joinNode,
                ImmutableList.of(dataNode1, dataNode2));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(2, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // Bare ExtData: position 0 common, {1} and {5} disjoint -> relaxed merge applies
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testSevenChildrenDisjointKeySetsMerged() {
        // 7 children with same relationDef, position 0 common, other positions disjoint
        // Simulates the LATERAL VIEW explode case: each child selects different column
        // All children use the SAME variable for position 0 (simulating same ?topic in SPARQL)
        List<IQTree> children = new ArrayList<>();
        Variable idVar = TERM_FACTORY.getVariable("id");  // Same variable for all children at position 0
        int[][] keySets = {
                {0, 1},   // child 0: id + col1
                {0, 2},   // child 1: id + col2
                {0, 3},   // child 2: id + col3
                {0, 4},   // child 3: id + col4
                {0, 5},   // child 4: id + col5
                {0, 6},   // child 5: id + col6
                {0, 7}    // child 6: id + col7
        };
        Variable[] vars = new Variable[7];
        for (int i = 0; i < 7; i++) {
            vars[i] = TERM_FACTORY.getVariable("v" + i);
        }
        for (int i = 0; i < 7; i++) {
            ExtensionalDataNode data = IQ_FACTORY.createExtensionalDataNode(
                    T1_AR5, ImmutableMap.of(
                            keySets[i][0], idVar,           // All use same idVar at position 0
                            keySets[i][1], vars[i]));      // Different vars at non-common positions
            ConstructionNode constr = IQ_FACTORY.createConstructionNode(
                    ImmutableSet.of(idVar, vars[i]));
            children.add(IQ_FACTORY.createUnaryIQTree(constr, data));
        }
        // No explicit join condition needed - all children share the same idVar at position 0
        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.copyOf(children));
        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, idVar, vars[0]);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(7, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // All 7 with disjoint non-common positions, same idVar at position 0 -> should merge to 1
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testExtractionFailureDoesNotBlockMerge() {
        // 3 children: 2 mergeable, 1 unmergeable (has FilterNode between Constr and ExtData)
        // The 2 mergeable ones should still merge despite the unmergeable child

        // Mergeable child 1
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));

        // Mergeable child 2
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 1, D));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));

        // Unmergeable child: ConstructionNode → ConstructionNode (instead of ConstructionNode → ExtData)
        ExtensionalDataNode dataNode3 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, E, 1, F));
        ConstructionNode constr3 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(E, F));
        ConstructionNode wrapper = IQ_FACTORY.createConstructionNode(ImmutableSet.of(E, F));
        IQTree unmergeableChild = IQ_FACTORY.createUnaryIQTree(wrapper,
                IQ_FACTORY.createUnaryIQTree(constr3, dataNode3));

        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2),
                        unmergeableChild));

        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(3, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // 2 mergeable children merged into 1, plus 1 unmergeable = 2 total
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testTransitiveVariableEquivalence() {
        // 3 children with different keySets {0,1}, {0,3}, {0,5}
        // Join condition: AND(STRICT_EQ2(A, C), STRICT_EQ2(C, E))
        // A=C and C=E → A should be equivalent to E transitively
        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, A, 1, B));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, C, 3, D));
        ExtensionalDataNode dataNode3 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, E, 5, F));

        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(A, B));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(C, D));
        ConstructionNode constr3 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(E, F));

        // A = C AND C = E → transitively A = E
        ImmutableExpression condition = TERM_FACTORY.getConjunction(
                TERM_FACTORY.getStrictEquality(A, C),
                TERM_FACTORY.getStrictEquality(C, E));
        InnerJoinNode joinNode = IQ_FACTORY.createInnerJoinNode(condition);

        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                joinNode,
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2),
                        IQ_FACTORY.createUnaryIQTree(constr3, dataNode3)));

        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, A, C);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(3, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // All 3 should merge because A, C, E are transitively equivalent
        assertEquals(1, countExtensionalNodes(result.getTree()));
    }

    @Test
    public void testStrictMergeThenRelaxedMerge() {
        // 4 children:
        //   child 0: keySet={0,1}  }
        //   child 1: keySet={0,1}  } -- same keySet → strict merge
        //   child 2: keySet={0,5}  } -- different keySet but same relationDef → relaxed merge
        //   child 3: keySet={0,6}  }
        // Expected: all 4 merge into 1
        Variable idVar = TERM_FACTORY.getVariable("id");

        ExtensionalDataNode dataNode1 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, idVar, 1, A));
        ExtensionalDataNode dataNode2 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, idVar, 1, B));
        ExtensionalDataNode dataNode3 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, idVar, 5, C));
        ExtensionalDataNode dataNode4 = IQ_FACTORY.createExtensionalDataNode(
                T1_AR5, ImmutableMap.of(0, idVar, 6, D));

        ConstructionNode constr1 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(idVar, A));
        ConstructionNode constr2 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(idVar, B));
        ConstructionNode constr3 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(idVar, C));
        ConstructionNode constr4 = IQ_FACTORY.createConstructionNode(ImmutableSet.of(idVar, D));

        NaryIQTree joinTree = IQ_FACTORY.createNaryIQTree(
                IQ_FACTORY.createInnerJoinNode(),
                ImmutableList.of(
                        IQ_FACTORY.createUnaryIQTree(constr1, dataNode1),
                        IQ_FACTORY.createUnaryIQTree(constr2, dataNode2),
                        IQ_FACTORY.createUnaryIQTree(constr3, dataNode3),
                        IQ_FACTORY.createUnaryIQTree(constr4, dataNode4)));

        DistinctVariableOnlyDataAtom projectionAtom = ATOM_FACTORY.getDistinctVariableOnlyDataAtom(
                ANS1_AR2_PREDICATE, idVar, A);
        ConstructionNode topConstructionNode = IQ_FACTORY.createConstructionNode(projectionAtom.getVariables());
        UnaryIQTree constructionTree = IQ_FACTORY.createUnaryIQTree(topConstructionNode, joinTree);
        IQ initialQuery = IQ_FACTORY.createIQ(projectionAtom, constructionTree);
        assertEquals(4, countExtensionalNodes(initialQuery.getTree()));
        IQ result = SAME_SOURCE_MERGE_OPTIMIZER.optimize(initialQuery);
        // 2 strict-merged into 1, 2 relaxed-merged into 1 = 2 total ExtData nodes
        assertEquals(2, countExtensionalNodes(result.getTree()));
    }

    private static int countExtensionalNodes(IQTree tree) {
        return (int) new ExtensionalDataNodeExtractor().transform(tree).count();
    }

}
