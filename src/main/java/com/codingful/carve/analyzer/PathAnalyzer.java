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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Finds the longest simple call chains in the application-code subgraph.
 *
 * <h3>Algorithm</h3>
 * Two-phase approach:
 * <ol>
 *   <li><b>Depth phase</b> — DFS with back-edge detection to compute
 *       {@code maxReachableDepth[v]}: the length of the longest simple path
 *       reachable from each application node. Results are memoised; nodes on
 *       the current DFS stack are skipped to break cycles (conservative
 *       approximation — may undercount depth through cyclic sub-graphs, but
 *       is fast and accurate for acyclic stretches).</li>
 *   <li><b>Reconstruction phase</b> — from each candidate starting node,
 *       greedily follow the successor with the highest memoised depth to
 *       reconstruct a concrete path. Visited nodes are excluded to keep paths
 *       simple.</li>
 * </ol>
 *
 * Only application-code nodes are traversed (library stubs are excluded).
 */
public class PathAnalyzer {

    /** A single path result: ordered list of nodes from start to end. */
    public record LongestPath(List<MethodNode> nodes) {
        public int depth() { return nodes.size(); }

        /** True if any node in the path makes an external call. */
        public boolean hasExternalCalls() {
            return nodes.stream().anyMatch(MethodNode::makesExternalCall);
        }
    }

    private final CallGraph callGraph;

    public PathAnalyzer(CallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    /**
     * Returns the {@code topN} longest simple call paths in the application-code
     * subgraph, ordered longest first.
     */
    public List<LongestPath> findLongestPaths(int topN) {
        Set<MethodNode> appNodes = callGraph.applicationNodes();
        if (appNodes.isEmpty()) return List.of();

        // Phase 1 — compute max reachable depth from each node
        Map<String, Integer> maxDepth = new HashMap<>();
        for (MethodNode node : appNodes) {
            computeMaxDepth(node, new LinkedHashSet<>(), maxDepth, appNodes);
        }

        // Phase 2 — reconstruct paths greedily from the deepest starting nodes
        // Take extra candidates to ensure we find topN distinct paths
        List<MethodNode> candidates = appNodes.stream()
            .filter(n -> maxDepth.getOrDefault(n.getId(), 0) >= 1)
            .sorted(Comparator.comparingInt(n -> -maxDepth.getOrDefault(n.getId(), 0)))
            .limit((long) topN * 5)
            .toList();

        Set<String> seenPathKeys = new HashSet<>();
        List<LongestPath> result = new ArrayList<>();

        for (MethodNode start : candidates) {
            if (result.size() >= topN) break;

            List<MethodNode> path = reconstructPath(start, maxDepth, appNodes);
            if (path.size() < 2) continue;

            // Use the node-id sequence as a deduplication key
            String key = path.stream().map(MethodNode::getId).collect(Collectors.joining("\0"));
            if (seenPathKeys.add(key)) {
                result.add(new LongestPath(List.copyOf(path)));
            }
        }

        result.sort(Comparator.comparingInt(p -> -p.depth()));
        return Collections.unmodifiableList(result);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * Recursive DFS that computes the longest simple path length reachable from
     * {@code node} without revisiting any node currently on the DFS stack.
     */
    private int computeMaxDepth(MethodNode node,
                                LinkedHashSet<String> onStack,
                                Map<String, Integer> memo,
                                Set<MethodNode> appNodes) {
        String id = node.getId();
        if (memo.containsKey(id)) return memo.get(id);
        if (onStack.contains(id))  return 0;  // back edge: break cycle

        onStack.add(id);
        int max = 0;
        for (MethodNode succ : callGraph.successors(node)) {
            if (!appNodes.contains(succ)) continue;
            max = Math.max(max, 1 + computeMaxDepth(succ, onStack, memo, appNodes));
        }
        onStack.remove(id);

        memo.put(id, max);
        return max;
    }

    /**
     * Greedily reconstructs the longest path starting from {@code start} by
     * always following the unvisited successor with the highest memoised depth.
     */
    private List<MethodNode> reconstructPath(MethodNode start,
                                              Map<String, Integer> maxDepth,
                                              Set<MethodNode> appNodes) {
        List<MethodNode> path    = new ArrayList<>();
        Set<String>      visited = new HashSet<>();
        MethodNode       current = start;

        while (current != null && !visited.contains(current.getId())) {
            path.add(current);
            visited.add(current.getId());

            current = callGraph.successors(current).stream()
                .filter(appNodes::contains)
                .filter(n -> !visited.contains(n.getId()))
                .max(Comparator.comparingInt(n -> maxDepth.getOrDefault(n.getId(), 0)))
                .orElse(null);
        }
        return path;
    }
}
