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
import org.junit.Test;

import static it.unibz.inf.ontop.OptimizationTestingTools.*;
import static org.junit.Assert.*;

public class SameSourceMergeOptimizerTest {

    public static final RelationDefinition T1_AR5;

    static {
        OfflineMetadataProviderBuilder3 builder = createMetadataProviderBuilder();
        T1_AR5 = builder.createRelationWithStringAttributes(1, 5, true);
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

    private static int countExtensionalNodes(IQTree tree) {
        return (int) new ExtensionalDataNodeExtractor().transform(tree).count();
    }

}
