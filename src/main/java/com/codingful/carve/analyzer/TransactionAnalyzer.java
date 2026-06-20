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
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.TransactionPropagation;
import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Finds methods that are reachable from a {@code @Transactional} root
 * <em>within the same transaction scope</em> and that make an external call
 * (HTTP, messaging, or non-DB external I/O).
 *
 * <h3>Transaction scope modelling</h3>
 * <ul>
 *   <li>{@code REQUIRED} — joins the caller's tx; traversal continues.</li>
 *   <li>{@code REQUIRES_NEW} — suspends the outer tx; <em>stops</em> the
 *       current traversal branch (the callee starts a new scope).</li>
 *   <li>{@code NOT_SUPPORTED / NEVER} — no tx; stops the traversal branch.</li>
 *   <li>All others — treated as "within scope" for conservative analysis.</li>
 * </ul>
 *
 * <h3>Limitation (Spring AOP)</h3>
 * Spring proxies only intercept public method calls made <em>from outside</em>
 * the bean. A self-invocation (same object, different method) bypasses the proxy
 * and its {@code @Transactional}. This analyser cannot detect self-invocation
 * and will conservatively treat every call as if the annotation is honoured.
 */
public class TransactionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TransactionAnalyzer.class);

    private final CallGraph callGraph;

    public TransactionAnalyzer(CallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    /**
     * Returns all detected risks: each transactional root paired with
     * every external-call site reachable in the same tx scope.
     */
    public List<TransactionRisk> findRisks() {
        List<TransactionRisk> risks = new ArrayList<>();
        Graph<MethodNode, DefaultEdge> raw = callGraph.getRaw();
        BFSShortestPath<MethodNode, DefaultEdge> bfs = new BFSShortestPath<>(raw);

        for (MethodNode root : callGraph.transactionalNodes()) {
            log.debug("Analysing tx root: {}", root.getId());
            Set<MethodNode> visited = new HashSet<>();
            Deque<MethodNode> queue = new ArrayDeque<>();
            queue.add(root);

            while (!queue.isEmpty()) {
                MethodNode current = queue.poll();
                if (!visited.add(current)) continue;

                // Check whether this node makes an external call that is risky
                // inside a transaction (HTTP, MESSAGING — not JDBC/JPA which are
                // expected inside a tx).
                Set<ExternalCallType> risky = riskyExternalCalls(current);
                if (!risky.isEmpty() && !current.equals(root)) {
                    var graphPath = bfs.getPath(root, current);
                    List<MethodNode> path = graphPath != null
                        ? graphPath.getVertexList()
                        : List.of(root, current);

                    risks.add(new TransactionRisk(root, current, Set.copyOf(risky), path));
                    log.info("Risk: {} -> {} [{}]", root.getId(), current.getId(), risky);
                }

                // Continue traversal only if remaining in the same tx scope
                for (MethodNode successor : callGraph.successors(current)) {
                    if (continuesToxScope(successor)) {
                        queue.add(successor);
                    }
                }
            }
        }

        return risks;
    }

    /**
     * Returns only the risks where an HTTP call is made inside a tx.
     * This is the most actionable category: HTTP calls under a DB tx
     * hold the connection open for the duration of a network round-trip.
     */
    public List<TransactionRisk> findHttpInTransactionRisks() {
        return findRisks().stream().filter(TransactionRisk::involvesHttp).toList();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the subset of external calls that are considered risky
     * when inside a database transaction.
     */
    private static Set<ExternalCallType> riskyExternalCalls(MethodNode node) {
        Set<ExternalCallType> result = new HashSet<>(node.getExternalCalls());
        // JDBC/JPA inside a tx is expected — not a risk for our purposes here
        result.remove(ExternalCallType.JDBC);
        result.remove(ExternalCallType.JPA);
        return result;
    }

    /**
     * Returns true if the node's transaction annotation does NOT break the
     * enclosing transaction scope (i.e., the traversal should continue through it).
     */
    private static boolean continuesToxScope(MethodNode node) {
        if (!node.isTransactional()) return true;   // no annotation: inherits tx

        return switch (node.getPropagation()) {
            case REQUIRED, SUPPORTS, MANDATORY, NESTED -> true;
            // REQUIRES_NEW, NOT_SUPPORTED, NEVER start/remove a tx boundary
            default -> false;
        };
    }
}
