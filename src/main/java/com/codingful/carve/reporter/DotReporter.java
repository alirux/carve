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

package com.codingful.carve.reporter;

import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exports the call graph as a Graphviz DOT file.
 *
 * Nodes are colour-coded:
 * <ul>
 *   <li>Yellow — {@code @Transactional}</li>
 *   <li>Red    — makes an external call</li>
 *   <li>Orange — both</li>
 *   <li>Grey   — library / non-application node</li>
 *   <li>White  — plain application code</li>
 * </ul>
 *
 * Edges:
 * <ul>
 *   <li>Solid black — a call site in the source</li>
 *   <li>Dashed grey — inferred by class hierarchy analysis, resolving the only
 *       implementation of the interface; sound</li>
 *   <li>Dashed amber — inferred with several implementations to choose between;
 *       at most one such edge is real (see {@code docs/CHA.md} §6b)</li>
 *   <li>Solid red — on a transaction-risk path ({@link #writeWithRisks})</li>
 * </ul>
 *
 * Usage: {@code dot -Tsvg output.dot -o output.svg}
 */
public class DotReporter {

    private final CallGraph callGraph;
    private final Set<String> cyclicNodeIds;
    private final boolean multiProject;

    public DotReporter(CallGraph callGraph) {
        this(callGraph, List.of());
    }

    public DotReporter(CallGraph callGraph, List<Set<MethodNode>> cyclicClusters) {
        this.callGraph = callGraph;
        this.cyclicNodeIds = cyclicClusters.stream()
            .flatMap(Set::stream)
            .map(MethodNode::getId)
            .collect(Collectors.toSet());
        this.multiProject = callGraph.hasMultipleProjects();
    }

    /**
     * Writes the full call graph to {@code writer}.
     *
     * @param onlyApplicationCode if true, library stub nodes are omitted
     */
    public void write(Writer writer, boolean onlyApplicationCode) {
        writeHeaderComment(writer);
        DOTExporter<MethodNode, DefaultEdge> exporter = new DOTExporter<>();
        exporter.setGraphAttributeProvider(() -> Map.of(
            "rankdir", DefaultAttribute.createAttribute("LR"),
            "fontname", DefaultAttribute.createAttribute("Helvetica"),
            "nodesep", DefaultAttribute.createAttribute("0.5")
        ));

        exporter.setVertexIdProvider(n -> quote(n.getId()));
        exporter.setVertexAttributeProvider(n -> nodeAttributes(n));
        exporter.setEdgeAttributeProvider(DotReporter.this::edgeAttributes);

        var graph = onlyApplicationCode
            ? appSubgraph()
            : callGraph.getRaw();

        exporter.exportGraph(graph, writer);
    }

    /**
     * Highlights risk paths by colouring their edges red.
     * Writes the full call graph with highlighted risk edges.
     */
    public void writeWithRisks(Writer writer,
                               boolean onlyApplicationCode,
                               List<TransactionRisk> risks) {

        writeHeaderComment(writer);
        Set<String> riskEdgeIds = risks.stream()
            .flatMap(r -> edgePairs(r.path()).stream())
            .collect(Collectors.toSet());

        DOTExporter<MethodNode, DefaultEdge> exporter = new DOTExporter<>();
        exporter.setGraphAttributeProvider(() -> Map.of(
            "rankdir", DefaultAttribute.createAttribute("LR"),
            "fontname", DefaultAttribute.createAttribute("Helvetica")
        ));
        exporter.setVertexIdProvider(n -> quote(n.getId()));
        exporter.setVertexAttributeProvider(n -> nodeAttributes(n));
        exporter.setEdgeAttributeProvider(e -> {
            var raw = onlyApplicationCode ? appSubgraph() : callGraph.getRaw();
            MethodNode src = raw.getEdgeSource(e);
            MethodNode tgt = raw.getEdgeTarget(e);
            String key = src.getId() + "->" + tgt.getId();
            if (riskEdgeIds.contains(key)) {
                return Map.of(
                    "color", DefaultAttribute.createAttribute("red"),
                    "penwidth", DefaultAttribute.createAttribute("2.0")
                );
            }
            return edgeAttributes(e);
        });

        var graph = onlyApplicationCode ? appSubgraph() : callGraph.getRaw();
        exporter.exportGraph(graph, writer);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Draws CHA-inferred edges dashed, because they are not call sites present in
     * the source — and colours them by how much the inference is worth.
     *
     * <p>Grey when CHA resolved the only implementation of the interface: inferred,
     * but exact, and no less trustworthy than a solid edge. Amber when it was
     * choosing between several, so at most one of those edges is real. Amber is the
     * over-approximation; grey is not, and colouring both the same overstates the
     * imprecision several-fold. The hue matches the HTML viewers.
     *
     * <p>Unlike the collapsed class and package graphs, this export is per method
     * call, so the fan-out needs no aggregation: it is the count CHA recorded at
     * that call site, and the tooltip carries it.
     *
     * @see com.codingful.carve.graph.CallGraph#chaFanOut
     */
    private Map<String, Attribute> edgeAttributes(DefaultEdge e) {
        if (!callGraph.isChaEdge(e)) return Map.of();
        int fanOut = callGraph.chaFanOut(e);
        boolean ambiguous = fanOut > 1;
        return Map.of(
            "style", DefaultAttribute.createAttribute("dashed"),
            "color", DefaultAttribute.createAttribute(ambiguous ? "#c98a2e" : "gray60"),
            "tooltip", DefaultAttribute.createAttribute(ambiguous
                ? "inferred by class hierarchy analysis — one of " + fanOut
                  + " implementations, so this call may not happen at runtime"
                : "inferred by class hierarchy analysis — the only implementation")
        );
    }

    private static void writeHeaderComment(Writer writer) {
        try {
            writer.write(ReportMetadata.asLineComment() + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Attribute> nodeAttributes(MethodNode n) {
        Map<String, Attribute> attrs = new LinkedHashMap<>();

        String projectPrefix = (multiProject && !n.getProjectName().isEmpty())
            ? "[" + n.getProjectName() + "]\\n" : "";
        String label = projectPrefix + n.getClassName() + "\\n" + n.getMethodName();
        attrs.put("label",   DefaultAttribute.createAttribute(label));
        attrs.put("shape",   DefaultAttribute.createAttribute("box"));
        attrs.put("style",   DefaultAttribute.createAttribute("filled"));
        attrs.put("fontname",DefaultAttribute.createAttribute("Helvetica"));
        attrs.put("fontsize",DefaultAttribute.createAttribute("10"));

        String color;
        if (!n.isApplicationCode()) {
            color = "lightgrey";
        } else if (n.isTransactional() && n.makesExternalCall()) {
            color = "orange";
        } else if (n.isTransactional()) {
            color = "lightyellow";
        } else if (n.makesExternalCall()) {
            color = "lightcoral";
        } else {
            color = "white";
        }
        attrs.put("fillcolor", DefaultAttribute.createAttribute(color));

        if (cyclicNodeIds.contains(n.getId())) {
            attrs.put("peripheries", DefaultAttribute.createAttribute("2"));
        }

        if (n.isTransactional()) {
            String tooltip = "TX:" + n.getPropagation()
                + (n.isReadOnly() ? " readOnly" : "");
            attrs.put("tooltip", DefaultAttribute.createAttribute(tooltip));
        }

        return attrs;
    }

    private org.jgrapht.Graph<MethodNode, DefaultEdge> appSubgraph() {
        Set<MethodNode> appNodes = callGraph.applicationNodes();
        return new org.jgrapht.graph.AsSubgraph<>(callGraph.getRaw(), appNodes);
    }

    private static Set<String> edgePairs(List<MethodNode> path) {
        Set<String> pairs = new java.util.HashSet<>();
        for (int i = 0; i < path.size() - 1; i++) {
            pairs.add(path.get(i).getId() + "->" + path.get(i + 1).getId());
        }
        return pairs;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
