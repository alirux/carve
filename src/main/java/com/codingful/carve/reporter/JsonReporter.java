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

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.codingful.carve.analyzer.CouplingAnalyzer;
import com.codingful.carve.analyzer.LockRiskAnalyzer;
import com.codingful.carve.analyzer.PathAnalyzer;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces a JSON report containing:
 * <ul>
 *   <li>Graph summary (node/edge counts, application vs library breakdown)</li>
 *   <li>List of transaction risks (root → external-call site + path)</li>
 *   <li>Package coupling metrics</li>
 * </ul>
 */
public class JsonReporter {

    // Jackson 3 ObjectMappers are immutable — configure via the builder.
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final CallGraph callGraph;

    public JsonReporter(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    public void write(Writer writer,
                      List<TransactionRisk> risks,
                      List<PathAnalyzer.LongestPath> longestPaths,
                      List<Set<MethodNode>> cyclicClusters,
                      Map<String, CouplingAnalyzer.PackageCoupling> coupling,
                      List<LockRiskAnalyzer.NestedTxRisk> nestedTxRisks,
                      List<LockRiskAnalyzer.CyclicTxRisk> cyclicTxRisks)
        throws IOException {

        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("summary",          buildSummary());
        report.put("transactionRisks", risks.stream().map(this::riskToMap).toList());
        report.put("longestPaths",     longestPaths.stream().map(this::pathToMap).toList());
        report.put("cyclicClusters",   cyclicClusters.stream().map(this::clusterToMap).toList());
        report.put("packageCoupling",  buildPackageCoupling(coupling));
        report.put("lockRisks", Map.of(
            "nestedRequiresNew",   nestedTxRisks.stream().map(this::nestedTxRiskToMap).toList(),
            "cyclicTransactional", cyclicTxRisks.stream().map(this::cyclicTxRiskToMap).toList()
        ));

        MAPPER.writeValue(writer, report);
    }

    // -----------------------------------------------------------------------

    private Map<String, Object> buildSummary() {
        long appCount = callGraph.applicationNodes().size();
        long txCount  = callGraph.transactionalNodes().size();

        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("totalVertices",        callGraph.vertexCount());
        map.put("totalEdges",           callGraph.edgeCount());
        map.put("applicationMethods",   appCount);
        map.put("transactionalMethods", txCount);
        map.put("libraryStubs",         callGraph.vertexCount() - appCount);

        // Multi-project: list named projects and per-project method counts
        if (callGraph.hasMultipleProjects()) {
            var byProject = callGraph.applicationNodes().stream()
                .filter(n -> !n.getProjectName().isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(
                    MethodNode::getProjectName,
                    java.util.TreeMap::new,
                    java.util.stream.Collectors.counting()));
            map.put("projects", byProject);
        }

        return map;
    }

    private Map<String, Object> riskToMap(TransactionRisk risk) {
        return Map.of(
            "transactionalRoot", risk.transactionalRoot().getId(),
            "externalCallSite",  risk.externalCallSite().getId(),
            "callTypes",         risk.callTypes().stream()
                                     .map(Enum::name).toList(),
            "path",              risk.path().stream()
                                     .map(MethodNode::getId).toList()
        );
    }

    private Map<String, Object> clusterToMap(Set<MethodNode> cluster) {
        return Map.of(
            "size",    cluster.size(),
            "methods", cluster.stream().map(MethodNode::getId).sorted().toList()
        );
    }

    private Map<String, Object> pathToMap(PathAnalyzer.LongestPath lp) {
        return Map.of(
            "depth", lp.depth(),
            "path",  lp.nodes().stream().map(MethodNode::getId).toList()
        );
    }

    private Map<String, Object> nestedTxRiskToMap(LockRiskAnalyzer.NestedTxRisk r) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("outerRoot",       r.outerRoot().getId());
        map.put("requiresNewSite", r.requiresNewSite().getId());
        map.put("path",            r.path().stream().map(MethodNode::getId).toList());
        return map;
    }

    private Map<String, Object> cyclicTxRiskToMap(LockRiskAnalyzer.CyclicTxRisk r) {
        return Map.of(
            "clusterSize",         r.cluster().size(),
            "transactionalCount",  r.transactionalNodes().size(),
            "transactionalMethods", r.transactionalNodes().stream()
                                      .map(MethodNode::getId).sorted().toList(),
            "allMethods",          r.cluster().stream()
                                      .map(MethodNode::getId).sorted().toList()
        );
    }

    /**
     * Builds the {@code packageCoupling} section: the flat {@code packages}
     * list (every package, sorted by descending instability) plus a
     * {@code hotspots} object grouping application packages into the three
     * modernisation archetypes.
     */
    private Map<String, Object> buildPackageCoupling(
            Map<String, CouplingAnalyzer.PackageCoupling> coupling) {

        var packages = coupling.values().stream()
            .map(this::couplingToMap)
            .sorted(java.util.Comparator.comparingDouble(
                m -> -((double) ((Map<?,?>) m).get("instability"))))
            .toList();

        var hotspots = CouplingAnalyzer.classifyHotspots(coupling.values());

        var hotspotsMap = new java.util.LinkedHashMap<String, Object>();
        hotspotsMap.put("unstableHubs",         hotspots.unstableHubs().stream().map(this::hotspotToMap).toList());
        hotspotsMap.put("extractionCandidates", hotspots.extractionCandidates().stream().map(this::hotspotToMap).toList());
        hotspotsMap.put("stableCores",          hotspots.stableCores().stream().map(this::hotspotToMap).toList());

        var section = new java.util.LinkedHashMap<String, Object>();
        section.put("hotspots", hotspotsMap);
        section.put("packages", packages);
        return section;
    }

    private Map<String, Object> couplingToMap(CouplingAnalyzer.PackageCoupling c) {
        return Map.of(
            "package",              c.packageName(),
            "applicationCode",      c.applicationCode(),
            "efferentCe",           c.ce(),
            "afferentCa",           c.ca(),
            "instability",          Math.round(c.instability() * 100.0) / 100.0,
            "efferentDependencies", c.efferentDependencies(),
            "afferentDependencies", c.afferentDependencies()
        );
    }

    private Map<String, Object> hotspotToMap(CouplingAnalyzer.PackageHotspot h) {
        return Map.of(
            "package",     h.packageName(),
            "afferentCa",  h.afferentCa(),
            "efferentCe",  h.efferentCe(),
            "instability", Math.round(h.instability() * 100.0) / 100.0,
            "score",       Math.round(h.score() * 100.0) / 100.0
        );
    }
}
