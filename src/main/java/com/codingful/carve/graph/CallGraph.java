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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thin wrapper around a JGraphT directed graph of {@link MethodNode} vertices.
 *
 * All mutating operations go through this class so the underlying graph type
 * can be swapped (e.g., weighted, labeled edges) without touching the extractors.
 */
public class CallGraph {

    private final Graph<MethodNode, DefaultEdge> graph =
        new DefaultDirectedGraph<>(DefaultEdge.class);

    /**
     * Registry of every parsed application <em>type</em>, keyed by FQN, with its
     * authoritative project attribution (resolved from the type's own source
     * file). Independent of whether the type has any method nodes — so a
     * method-less DTO (e.g. a pure Lombok {@code @Data} class) is still known
     * and correctly attributed.
     */
    public record TypeInfo(String fqn, String label, String project) {}

    private final Map<String, TypeInfo> types = new LinkedHashMap<>();

    /**
     * Edges introduced by Class Hierarchy Analysis rather than by a call site in
     * the source. They are over-approximations: CHA links a virtual call to every
     * implementation it can see, so some of these calls cannot happen at runtime.
     * Keeping them identifiable lets consumers exclude them from coupling metrics.
     *
     * <p>{@link DefaultEdge} has identity semantics, so a plain set is enough.
     */
    private final Set<DefaultEdge> chaEdges = new HashSet<>();

    /**
     * For each inferred edge, how many implementations of the interface CHA had to
     * choose between when it created that edge.
     *
     * <p>A fan-out of 1 means the interface had a single implementation in the
     * analysed source: CHA did not guess, it resolved the only candidate, and the
     * edge is as trustworthy as a direct one. Higher values are the genuine
     * over-approximation — one of the {@code n} edges is real and the rest are not.
     * Recording the number turns "this edge may be wrong" into "this edge is one of
     * n candidates", which is what a consumer needs to decide whether to check it.
     */
    private final Map<DefaultEdge, Integer> chaFanOut = new HashMap<>();

    /** Count of parsed types carrying a Lombok annotation (see {@link #lombokDetected}). */
    private int lombokAnnotatedTypes;

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /** Adds a vertex; silently ignores duplicates (JGraphT contract). */
    public void addVertex(MethodNode node) {
        graph.addVertex(node);
    }

    /** Records a parsed type and its project. First registration wins. */
    public void registerType(String fqn, String label, String project) {
        types.putIfAbsent(fqn, new TypeInfo(fqn, label, project));
    }

    /** Records that a parsed type carries a Lombok annotation. */
    public void markLombokType() {
        lombokAnnotatedTypes++;
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

    /**
     * Adds an edge derived from Class Hierarchy Analysis, tagging it as such.
     *
     * <p>An edge that already exists is left tagged as it was: a call the source
     * makes directly stays {@code direct} even when CHA rediscovers it, since the
     * direct evidence is the stronger claim.
     *
     * <p>The same caller/callee pair can however be reached by CHA more than once,
     * through two different interfaces that the callee implements. The fan-outs then
     * differ, and keeping whichever arrived first would make the recorded value
     * depend on the iteration order of the implementor index — a {@code HashMap},
     * so not a stable order. The fan-outs are merged by {@code max} instead: the
     * value is order-independent, and it reports the worst ambiguity the edge rests
     * on, which is the same rule the class-level collapse applies.
     *
     * @param implFanOut how many implementations of the interface CHA was choosing
     *                   between; 1 means the interface had exactly one, so the edge
     *                   is resolved rather than guessed
     * @return true when a new edge was created and tagged as CHA-derived
     */
    public boolean addChaEdge(MethodNode caller, MethodNode callee, int implFanOut) {
        if (caller.equals(callee)) return false;
        graph.addVertex(caller);
        graph.addVertex(callee);
        DefaultEdge edge = graph.addEdge(caller, callee);
        if (edge == null) {
            // Already present. Only deepen the ambiguity of an edge CHA itself made;
            // one observed at a call site keeps no fan-out at all.
            DefaultEdge existing = graph.getEdge(caller, callee);
            if (chaEdges.contains(existing)) {
                chaFanOut.merge(existing, implFanOut, Integer::max);
            }
            return false;
        }
        chaEdges.add(edge);
        chaFanOut.put(edge, implFanOut);
        return true;
    }

    /** Convenience for the single-implementation case: an exactly resolved inferred edge. */
    public boolean addChaEdge(MethodNode caller, MethodNode callee) {
        return addChaEdge(caller, callee, 1);
    }

    // -----------------------------------------------------------------------
    // Read-only access
    // -----------------------------------------------------------------------

    /** Returns the raw JGraphT graph for use with JGraphT algorithm classes. */
    public Graph<MethodNode, DefaultEdge> getRaw() { return graph; }

    public Set<MethodNode> vertices()  { return graph.vertexSet(); }
    public Set<DefaultEdge> edges()    { return graph.edgeSet(); }

    /**
     * True when this edge was added by Class Hierarchy Analysis instead of being
     * observed at a call site. See {@link #addChaEdge}.
     */
    public boolean isChaEdge(DefaultEdge edge) { return chaEdges.contains(edge); }

    /**
     * How many implementations CHA was choosing between when it inferred this edge,
     * or {@code 0} for an edge observed at a call site. See {@link #chaFanOut}.
     */
    public int chaFanOut(DefaultEdge edge) { return chaFanOut.getOrDefault(edge, 0); }

    /** Number of edges that exist only because of Class Hierarchy Analysis. */
    public int chaEdgeCount() { return chaEdges.size(); }

    /** All parsed application types, in first-seen order. */
    public Collection<TypeInfo> types() { return types.values(); }

    /** Number of parsed types carrying a Lombok annotation. */
    public int lombokAnnotatedTypeCount() { return lombokAnnotatedTypes; }

    /**
     * True when Lombok annotations were seen in the analysed source. Lombok's
     * generated members are not modelled (no delombok), so coupling via
     * generated accessors — and especially via {@code @Builder} chains — may be
     * incomplete. See {@code docs/LOMBOK.md}.
     */
    public boolean lombokDetected() { return lombokAnnotatedTypes > 0; }

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

    /** True when more than one named project is present across methods or types. */
    public boolean hasMultipleProjects() {
        return Stream.concat(
                graph.vertexSet().stream().map(MethodNode::getProjectName),
                types.values().stream().map(TypeInfo::project))
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
