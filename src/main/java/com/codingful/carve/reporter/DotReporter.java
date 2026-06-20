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
        DOTExporter<MethodNode, DefaultEdge> exporter = new DOTExporter<>();
        exporter.setGraphAttributeProvider(() -> Map.of(
            "rankdir", DefaultAttribute.createAttribute("LR"),
            "fontname", DefaultAttribute.createAttribute("Helvetica"),
            "nodesep", DefaultAttribute.createAttribute("0.5")
        ));

        exporter.setVertexIdProvider(n -> quote(n.getId()));
        exporter.setVertexAttributeProvider(n -> nodeAttributes(n));
        exporter.setEdgeAttributeProvider(e -> Map.of());

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
            return Map.of();
        });

        var graph = onlyApplicationCode ? appSubgraph() : callGraph.getRaw();
        exporter.exportGraph(graph, writer);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
