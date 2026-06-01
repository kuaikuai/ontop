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
        assertEquals(2, countExtensionalNodes(result.getTree()));
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

    private static int countExtensionalNodes(IQTree tree) {
        return (int) new ExtensionalDataNodeExtractor().transform(tree).count();
    }

}
