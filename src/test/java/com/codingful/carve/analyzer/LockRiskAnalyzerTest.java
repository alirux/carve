/*
 * Copyright (C) 2026 Alberto Lirussi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.codingful.carve.analyzer;

import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.TransactionPropagation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LockRiskAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MethodNode txNode(String id, TransactionPropagation prop) {
        MethodNode n = new MethodNode(id, "com.example", "Cls", id, "com.example.Cls", true);
        n.setTransactional(true);
        n.setPropagation(prop);
        return n;
    }

    private static MethodNode plain(String id) {
        return new MethodNode(id, "com.example", "Cls", id, "com.example.Cls", true);
    }

    // -----------------------------------------------------------------------
    // Pattern 1 — REQUIRES_NEW nested inside an open transaction
    // -----------------------------------------------------------------------

    @Test
    void detectsDirectRequiresNew() {
        CallGraph cg = new CallGraph();

        MethodNode root = txNode("outerTx", TransactionPropagation.REQUIRED);
        MethodNode site = txNode("innerNewTx", TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(site);
        cg.addEdge(root, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).outerRoot()).isEqualTo(root);
        assertThat(risks.get(0).requiresNewSite()).isEqualTo(site);
        assertThat(risks.get(0).path()).containsExactly(root, site);
    }

    @Test
    void detectsRequiresNewThroughIntermediaryChain() {
        CallGraph cg = new CallGraph();

        MethodNode root   = txNode("process", TransactionPropagation.REQUIRED);
        MethodNode middle = plain("delegate");
        MethodNode site   = txNode("audit", TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(middle);
        cg.addVertex(site);
        cg.addEdge(root, middle);
        cg.addEdge(middle, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).outerRoot()).isEqualTo(root);
        assertThat(risks.get(0).requiresNewSite()).isEqualTo(site);
        assertThat(risks.get(0).path()).containsExactly(root, middle, site);
    }

    @Test
    void deduplicatesSamePairFromMultiplePaths() {
        // root → middle1 → site  AND  root → middle2 → site
        // Should produce exactly one NestedTxRisk for (root, site).
        CallGraph cg = new CallGraph();

        MethodNode root    = txNode("root", TransactionPropagation.REQUIRED);
        MethodNode middle1 = plain("pathA");
        MethodNode middle2 = plain("pathB");
        MethodNode site    = txNode("site", TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(middle1);
        cg.addVertex(middle2);
        cg.addVertex(site);
        cg.addEdge(root, middle1);
        cg.addEdge(root, middle2);
        cg.addEdge(middle1, site);
        cg.addEdge(middle2, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).outerRoot()).isEqualTo(root);
        assertThat(risks.get(0).requiresNewSite()).isEqualTo(site);
    }

    @Test
    void scopeBoundaryBlocksTraversal_notSupported() {
        // root(REQUIRED) → boundary(NOT_SUPPORTED) → site(REQUIRES_NEW)
        // NOT_SUPPORTED suspends the outer tx, so the scope does not continue.
        CallGraph cg = new CallGraph();

        MethodNode root     = txNode("outerTx",  TransactionPropagation.REQUIRED);
        MethodNode boundary = txNode("noTxZone", TransactionPropagation.NOT_SUPPORTED);
        MethodNode site     = txNode("innerNew",  TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(boundary);
        cg.addVertex(site);
        cg.addEdge(root, boundary);
        cg.addEdge(boundary, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).isEmpty();
    }

    @Test
    void scopeBoundaryBlocksTraversal_never() {
        CallGraph cg = new CallGraph();

        MethodNode root     = txNode("outerTx", TransactionPropagation.REQUIRED);
        MethodNode boundary = txNode("neverTx", TransactionPropagation.NEVER);
        MethodNode site     = txNode("innerNew", TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(boundary);
        cg.addVertex(site);
        cg.addEdge(root, boundary);
        cg.addEdge(boundary, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).isEmpty();
    }

    @Test
    void supportsAndMandatoryAndNestedContinueScope() {
        // SUPPORTS, MANDATORY and NESTED all join (or require) the existing tx
        // scope — traversal must continue through them.
        for (TransactionPropagation passthrough :
                List.of(TransactionPropagation.SUPPORTS,
                        TransactionPropagation.MANDATORY,
                        TransactionPropagation.NESTED)) {

            CallGraph cg = new CallGraph();

            MethodNode root   = txNode("root",   TransactionPropagation.REQUIRED);
            MethodNode middle = txNode("middle",  passthrough);
            MethodNode site   = txNode("newSite", TransactionPropagation.REQUIRES_NEW);

            cg.addVertex(root);
            cg.addVertex(middle);
            cg.addVertex(site);
            cg.addEdge(root, middle);
            cg.addEdge(middle, site);

            List<LockRiskAnalyzer.NestedTxRisk> risks =
                new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

            // root(REQUIRED) → site must always produce a risk.
            // middle is also a lock-holding root so an additional (middle, site) risk may appear.
            assertThat(risks)
                .as("Expected nested-tx risk from root through propagation %s", passthrough)
                .anySatisfy(r -> {
                    assertThat(r.outerRoot()).isEqualTo(root);
                    assertThat(r.requiresNewSite()).isEqualTo(site);
                });
        }
    }

    @Test
    void nonTransactionalRootProducesNoRisk() {
        CallGraph cg = new CallGraph();

        MethodNode root = plain("service");
        MethodNode site = txNode("audit", TransactionPropagation.REQUIRES_NEW);

        cg.addVertex(root);
        cg.addVertex(site);
        cg.addEdge(root, site);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).isEmpty();
    }

    @Test
    void requiresNewRootDoesNotFlagItselfAsRisk() {
        // A lone REQUIRES_NEW node is a tx root, not a nested one.
        CallGraph cg = new CallGraph();

        MethodNode root = txNode("standalone", TransactionPropagation.REQUIRES_NEW);
        cg.addVertex(root);

        List<LockRiskAnalyzer.NestedTxRisk> risks =
            new LockRiskAnalyzer(cg).findNestedRequiresNewRisks();

        assertThat(risks).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Pattern 2 — @Transactional methods in cyclic call clusters
    // -----------------------------------------------------------------------

    @Test
    void detectsCyclicClusterWithTwoTransactionalNodes() {
        MethodNode a = txNode("a", TransactionPropagation.REQUIRED);
        MethodNode b = txNode("b", TransactionPropagation.REQUIRED);
        MethodNode c = plain("c");

        Set<MethodNode> cluster = Set.of(a, b, c);

        List<LockRiskAnalyzer.CyclicTxRisk> risks =
            new LockRiskAnalyzer(new CallGraph())
                .findCyclicTransactionRisks(List.of(cluster));

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).transactionalNodes()).containsExactlyInAnyOrder(a, b);
        assertThat(risks.get(0).cluster()).isEqualTo(cluster);
    }

    @Test
    void ignoresClusterWithOnlyOneTransactionalNode() {
        MethodNode a = txNode("a", TransactionPropagation.REQUIRED);
        MethodNode b = plain("b");

        List<LockRiskAnalyzer.CyclicTxRisk> risks =
            new LockRiskAnalyzer(new CallGraph())
                .findCyclicTransactionRisks(List.of(Set.of(a, b)));

        assertThat(risks).isEmpty();
    }

    @Test
    void ignoresClusterWithNoTransactionalNodes() {
        MethodNode a = plain("a");
        MethodNode b = plain("b");

        List<LockRiskAnalyzer.CyclicTxRisk> risks =
            new LockRiskAnalyzer(new CallGraph())
                .findCyclicTransactionRisks(List.of(Set.of(a, b)));

        assertThat(risks).isEmpty();
    }

    @Test
    void sortsResultsByTransactionalCountDescending() {
        MethodNode a = txNode("a", TransactionPropagation.REQUIRED);
        MethodNode b = txNode("b", TransactionPropagation.REQUIRED);
        MethodNode c = txNode("c", TransactionPropagation.REQUIRED);
        MethodNode d = plain("d");

        // Cluster 1: 2 @Transactional; cluster 2: 3 @Transactional
        List<LockRiskAnalyzer.CyclicTxRisk> risks =
            new LockRiskAnalyzer(new CallGraph())
                .findCyclicTransactionRisks(
                    List.of(Set.of(a, b), Set.of(b, c, d, txNode("e", TransactionPropagation.REQUIRED))));

        // First result should have the larger transactional count
        assertThat(risks.get(0).transactionalNodes().size())
            .isGreaterThanOrEqualTo(risks.get(1).transactionalNodes().size());
    }

    @Test
    void emptyClusterListProducesEmptyResult() {
        List<LockRiskAnalyzer.CyclicTxRisk> risks =
            new LockRiskAnalyzer(new CallGraph())
                .findCyclicTransactionRisks(List.of());

        assertThat(risks).isEmpty();
    }
}
