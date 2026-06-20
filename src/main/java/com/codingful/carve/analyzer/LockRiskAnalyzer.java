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
import org.jgrapht.alg.shortestpath.BFSShortestPath;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects structural patterns in the call graph that are prone to database
 * lock contention or deadlocks.
 *
 * <h3>Pattern 1 — REQUIRES_NEW nested inside an open transaction</h3>
 * When a {@code @Transactional} method calls (directly or transitively) a
 * method annotated with {@code REQUIRES_NEW}, the outer transaction is
 * suspended but its DB locks are still held while the inner transaction runs.
 * If both transactions touch the same tables — or if the connection pool is
 * small — the result is either a deadlock or connection starvation.
 *
 * <h3>Pattern 2 — @Transactional methods in cyclic call clusters (SCC)</h3>
 * When two or more {@code @Transactional} methods are mutually reachable
 * through the call graph, concurrent requests can enter them in different
 * orders. Each request starts its own DB transaction and acquires locks
 * in the order it visits the entities. If the orderings differ, classical
 * deadlock conditions can arise.
 *
 * <p>Static analysis cannot determine <em>which</em> tables or rows are
 * locked — these findings are structural risk patterns, not proof of
 * deadlock. Review each finding against actual entity access patterns.
 */
public class LockRiskAnalyzer {

    private final CallGraph callGraph;

    public LockRiskAnalyzer(CallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    // -----------------------------------------------------------------------
    // Pattern 1
    // -----------------------------------------------------------------------

    /**
     * Finds every case where a {@code REQUIRES_NEW} method is reachable from
     * a {@code @Transactional} root within the same outer transaction scope.
     *
     * <p>Results are deduplicated: if the same (root, REQUIRES_NEW site) pair
     * is reachable via multiple paths, only the shortest path is kept.
     */
    public List<NestedTxRisk> findNestedRequiresNewRisks() {
        var bfs = new BFSShortestPath<>(callGraph.getRaw());
        // Use a map keyed by (root id, site id) to keep shortest-path only
        Map<String, NestedTxRisk> seen = new LinkedHashMap<>();

        for (MethodNode root : callGraph.transactionalNodes()) {
            if (!isLockHoldingPropagation(root)) continue;
            Set<MethodNode> visited = new HashSet<>();
            Deque<MethodNode> queue = new ArrayDeque<>();
            queue.add(root);

            while (!queue.isEmpty()) {
                MethodNode current = queue.poll();
                if (!visited.add(current)) continue;

                for (MethodNode successor : callGraph.successors(current)) {
                    if (isRequiresNew(successor)) {
                        String key = root.getId() + "|" + successor.getId();
                        seen.computeIfAbsent(key, k -> {
                            var p = bfs.getPath(root, successor);
                            List<MethodNode> path = p != null
                                ? p.getVertexList() : List.of(root, successor);
                            return new NestedTxRisk(root, successor, path);
                        });
                        // Do not follow into the REQUIRES_NEW scope
                    } else if (continuesScope(successor)) {
                        queue.add(successor);
                    }
                }
            }
        }

        return List.copyOf(seen.values());
    }

    // -----------------------------------------------------------------------
    // Pattern 2
    // -----------------------------------------------------------------------

    /**
     * Filters the given cyclic clusters, returning those that contain at least
     * two {@code @Transactional} methods. Two or more independent transaction
     * entry points in the same call cycle create conditions for mutual lock
     * acquisition in different orders across concurrent requests.
     *
     * <p>Results are sorted descending by number of transactional nodes in
     * the cluster.
     */
    public List<CyclicTxRisk> findCyclicTransactionRisks(List<Set<MethodNode>> cyclicClusters) {
        return cyclicClusters.stream()
            .map(cluster -> {
                Set<MethodNode> txNodes = cluster.stream()
                    .filter(MethodNode::isTransactional)
                    .collect(Collectors.toUnmodifiableSet());
                return new CyclicTxRisk(cluster, txNodes);
            })
            .filter(r -> r.transactionalNodes().size() >= 2)
            .sorted(Comparator.comparingInt(
                (CyclicTxRisk r) -> r.transactionalNodes().size()).reversed())
            .toList();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** True for propagations that hold DB locks while the method executes. */
    private static boolean isLockHoldingPropagation(MethodNode node) {
        return switch (node.getPropagation()) {
            case REQUIRED, SUPPORTS, MANDATORY, NESTED, REQUIRES_NEW -> true;
            default -> false;  // NOT_SUPPORTED, NEVER — no active transaction, no locks held
        };
    }

    private static boolean isRequiresNew(MethodNode node) {
        return node.isTransactional()
            && node.getPropagation() == TransactionPropagation.REQUIRES_NEW;
    }

    private static boolean continuesScope(MethodNode node) {
        if (!node.isTransactional()) return true;
        return switch (node.getPropagation()) {
            case REQUIRED, SUPPORTS, MANDATORY, NESTED -> true;
            default -> false;
        };
    }

    // -----------------------------------------------------------------------
    // Result types
    // -----------------------------------------------------------------------

    /**
     * A {@code REQUIRES_NEW} transaction site reachable from an outer
     * {@code @Transactional} root while the outer transaction is still open.
     */
    public record NestedTxRisk(
        MethodNode outerRoot,
        MethodNode requiresNewSite,
        List<MethodNode> path
    ) {}

    /**
     * A cyclic call cluster (SCC) that contains two or more
     * {@code @Transactional} methods — potential mutual-lock deadlock.
     */
    public record CyclicTxRisk(
        Set<MethodNode> cluster,
        Set<MethodNode> transactionalNodes
    ) {}
}
