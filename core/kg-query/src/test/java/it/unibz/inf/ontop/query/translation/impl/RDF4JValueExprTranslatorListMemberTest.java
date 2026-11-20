package it.unibz.inf.ontop.query.translation.impl;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the folding strategy used to translate a SPARQL
 * {@code FILTER(?v IN (v1, ..., vN))} into an RDF-level disjunction.
 *
 * <p>Historically the members were folded <em>left</em> ({@code Stream.reduce}), producing a
 * left-deep binary {@code LOGICAL_OR} chain whose depth is {@code N - 1}. Because the resulting
 * term is traversed recursively by {@code simplify()} / {@code toString()} / {@code hashCode()},
 * large lists (645+ members) overflowed the JVM stack.
 *
 * <p>{@link RDF4JValueExprTranslator#balancedFold} folds the same items into a balanced tree
 * (height {@code ceil(log2 N)}) while preserving their order.
 */
public class RDF4JValueExprTranslatorListMemberTest {

    /** Minimal binary tree used to observe the shape produced by the fold. */
    private static final class Node {
        private final String label;     // non-null for a leaf
        private final Node left;        // non-null for an internal node
        private final Node right;

        private Node(String label) {
            this.label = label;
            this.left = null;
            this.right = null;
        }

        private Node(Node left, Node right) {
            this.label = null;
            this.left = left;
            this.right = right;
        }

        private boolean isLeaf() {
            return label != null;
        }
    }

    private static ImmutableList<Node> leaves(int n) {
        ImmutableList.Builder<Node> builder = ImmutableList.builder();
        for (int i = 0; i < n; i++) {
            builder.add(new Node("v" + i));
        }
        return builder.build();
    }

    /** Number of nodes on the longest root-to-leaf path. */
    private static int nodeCountDepth(Node node) {
        int left = node.left == null ? 0 : nodeCountDepth(node.left);
        int right = node.right == null ? 0 : nodeCountDepth(node.right);
        return 1 + Math.max(left, right);
    }

    private static void inOrder(Node node, List<String> out) {
        if (node.isLeaf()) {
            out.add(node.label);
            return;
        }
        inOrder(node.left, out);
        inOrder(node.right, out);
    }

    /** Number of fold rounds: ceil(log2(n)), i.e. the height (in edges) of a balanced tree. */
    private static int expectedHeight(int n) {
        int height = 0;
        int remaining = n;
        while (remaining > 1) {
            remaining = (remaining + 1) / 2;
            height++;
        }
        return height;
    }

    @Test
    public void foldIsBalancedInsteadOfLeftDeep() {
        for (int n : new int[] { 2, 3, 4, 20, 200, 1001 }) {
            Node root = RDF4JValueExprTranslator.balancedFold(leaves(n), Node::new);

            // node-count depth = height in edges + 1
            assertEquals(expectedHeight(n) + 1, nodeCountDepth(root),
                    "unexpected fold depth for N=" + n);
        }

        // For N >= 4 a balanced fold is strictly shallower than a left-deep one (height N-1).
        // (For N <= 3 both shapes coincide.)
        for (int n : new int[] { 4, 20, 200, 1001 }) {
            Node root = RDF4JValueExprTranslator.balancedFold(leaves(n), Node::new);
            assertTrue(nodeCountDepth(root) - 1 < n - 1,
                    "the fold must not be left-deep for N=" + n);
        }
    }

    @Test
    public void foldPreservesTheOrderOfTheItems() {
        int n = 1001;
        Node root = RDF4JValueExprTranslator.balancedFold(leaves(n), Node::new);

        List<String> inOrderLabels = new ArrayList<>();
        inOrder(root, inOrderLabels);

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            expected.add("v" + i);
        }
        assertEquals(expected, inOrderLabels);
    }

    @Test
    public void foldAppliesTheCombinerExactlyOncePerPair() {
        for (int n : new int[] { 1, 2, 3, 20, 1001 }) {
            AtomicInteger applications = new AtomicInteger();
            RDF4JValueExprTranslator.balancedFold(leaves(n), (left, right) -> {
                applications.incrementAndGet();
                return new Node(left, right);
            });
            assertEquals(n - 1, applications.get(), "unexpected number of combinations for N=" + n);
        }
    }

    @Test
    public void singleItemIsReturnedAsIs() {
        ImmutableList<Node> items = leaves(1);
        Node only = items.get(0);
        assertEquals(only, RDF4JValueExprTranslator.balancedFold(items, Node::new));
    }
}
