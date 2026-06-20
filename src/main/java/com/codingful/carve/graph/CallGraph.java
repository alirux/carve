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

package com.codingful.carve.graph;

import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thin wrapper around a JGraphT directed graph of {@link MethodNode} vertices.
 *
 * All mutating operations go through this class so the underlying graph type
 * can be swapped (e.g., weighted, labeled edges) without touching the extractors.
 */
public class CallGraph {

    private final Graph<MethodNode, DefaultEdge> graph =
        new DefaultDirectedGraph<>(DefaultEdge.class);

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /** Adds a vertex; silently ignores duplicates (JGraphT contract). */
    public void addVertex(MethodNode node) {
        graph.addVertex(node);
    }

    /**
     * Adds a directed edge from {@code caller} to {@code callee}.
     * Both vertices must already be present (or will be added defensively).
     * Self-loops are skipped — they add noise without analytical value.
     */
    public void addEdge(MethodNode caller, MethodNode callee) {
        if (caller.equals(callee)) return;
        graph.addVertex(caller);
        graph.addVertex(callee);
        graph.addEdge(caller, callee);
    }

    // -----------------------------------------------------------------------
    // Read-only access
    // -----------------------------------------------------------------------

    /** Returns the raw JGraphT graph for use with JGraphT algorithm classes. */
    public Graph<MethodNode, DefaultEdge> getRaw() { return graph; }

    public Set<MethodNode> vertices()  { return graph.vertexSet(); }
    public Set<DefaultEdge> edges()    { return graph.edgeSet(); }

    public Iterable<MethodNode> successors(MethodNode node) {
        return () -> graph.outgoingEdgesOf(node).stream()
            .map(graph::getEdgeTarget)
            .iterator();
    }

    public Iterable<MethodNode> predecessors(MethodNode node) {
        return () -> graph.incomingEdgesOf(node).stream()
            .map(graph::getEdgeSource)
            .iterator();
    }

    // -----------------------------------------------------------------------
    // Filtered views — convenience for analyzers
    // -----------------------------------------------------------------------

    public Set<MethodNode> applicationNodes() {
        return graph.vertexSet().stream()
            .filter(MethodNode::isApplicationCode)
            .collect(Collectors.toSet());
    }

    public Set<MethodNode> transactionalNodes() {
        return graph.vertexSet().stream()
            .filter(MethodNode::isTransactional)
            .collect(Collectors.toSet());
    }

    public Set<MethodNode> nodesWithExternalCall(ExternalCallType type) {
        return graph.vertexSet().stream()
            .filter(n -> n.getExternalCalls().contains(type))
            .collect(Collectors.toSet());
    }

    public Set<MethodNode> nodesOfType(SpringComponentType type) {
        return graph.vertexSet().stream()
            .filter(n -> n.getComponentType() == type)
            .collect(Collectors.toSet());
    }

    /** True when nodes from more than one named project are present. */
    public boolean hasMultipleProjects() {
        return graph.vertexSet().stream()
            .map(MethodNode::getProjectName)
            .filter(p -> !p.isEmpty())
            .distinct()
            .count() > 1;
    }

    public int vertexCount() { return graph.vertexSet().size(); }
    public int edgeCount()   { return graph.edgeSet().size(); }

    @Override
    public String toString() {
        return "CallGraph{vertices=" + vertexCount() + ", edges=" + edgeCount() + "}";
    }
}
