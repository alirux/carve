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

import com.codingful.carve.analyzer.CouplingAnalyzer.PackageHotspot;
import com.codingful.carve.util.Fqns;

import java.util.*;

/**
 * A package-level collapse of the class graph.
 *
 * <p>Each node represents one Java package. Attributes are the union/aggregation
 * of the underlying class nodes. Edges are package-to-package, weighted by the
 * total number of inter-package class-to-class calls.
 */
public final class PackageGraphModel {

    public record Node(
        String id,
        String label,
        String project,
        int classes,
        boolean transactional,
        boolean external,
        boolean cyclic,
        boolean inRisk,
        boolean inLockRisk,
        /** Modernisation archetype key (e.g. {@code "unstableHubs"}) or {@code null} if not a hotspot. */
        String archetype,
        /** Hotspot ranking score within its archetype; {@code 0} when not a hotspot. */
        double score
    ) {}

    public record Edge(String source, String target, int weight) {}

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final boolean multiProject;
    private final int lombokAnnotatedTypes;

    private PackageGraphModel(List<Node> nodes, List<Edge> edges,
                             boolean multiProject, int lombokAnnotatedTypes) {
        this.nodes = nodes;
        this.edges = edges;
        this.multiProject = multiProject;
        this.lombokAnnotatedTypes = lombokAnnotatedTypes;
    }

    public List<Node> nodes()          { return nodes; }
    public List<Edge> edges()          { return edges; }
    public boolean multiProject()      { return multiProject; }
    public int lombokAnnotatedTypes()  { return lombokAnnotatedTypes; }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    public static PackageGraphModel collapse(ClassGraphModel classModel) {
        return collapse(classModel, Map.of());
    }

    /**
     * Collapses the class graph to package level, tagging each package with its
     * modernisation {@link PackageHotspot} (if any) so the viewer can highlight
     * the {@code unstableHubs}, {@code extractionCandidates} and {@code stableCores}.
     *
     * @param hotspotsByPackage package name → its hotspot classification
     */
    public static PackageGraphModel collapse(ClassGraphModel classModel,
                                             Map<String, PackageHotspot> hotspotsByPackage) {
        Map<String, PkgAcc> byPkg = new LinkedHashMap<>();

        for (ClassGraphModel.Node cn : classModel.nodes()) {
            String pkg = pkgKey(cn.id());
            PkgAcc acc = byPkg.computeIfAbsent(pkg,
                k -> new PkgAcc(shortLabel(k), cn.project()));
            acc.classes++;
            if (cn.transactional()) acc.transactional = true;
            if (cn.external())      acc.external      = true;
            if (cn.cyclic())        acc.cyclic        = true;
            if (cn.inRisk())        acc.inRisk        = true;
            if (cn.inLockRisk())    acc.inLockRisk    = true;
            // track dominant project by class count
            acc.projectCounts.merge(cn.project(), 1, Integer::sum);
        }

        // Aggregate class-level edges to package-level
        Map<String, Integer> edgeWeights = new LinkedHashMap<>();
        for (ClassGraphModel.Edge ce : classModel.edges()) {
            String sp = pkgKey(ce.source());
            String tp = pkgKey(ce.target());
            if (sp.equals(tp)) continue;
            edgeWeights.merge(sp + " " + tp, ce.weight(), Integer::sum);
        }

        List<Node> nodes = new ArrayList<>(byPkg.size());
        byPkg.forEach((pkg, acc) -> {
            String dominant = acc.projectCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
            PackageHotspot hotspot = hotspotsByPackage.get(pkg);
            String archetype = hotspot != null ? hotspot.archetype().jsonKey() : null;
            double score     = hotspot != null ? hotspot.score() : 0.0;
            nodes.add(new Node(pkg, acc.label, dominant, acc.classes,
                acc.transactional, acc.external, acc.cyclic, acc.inRisk, acc.inLockRisk,
                archetype, score));
        });

        List<Edge> edges = new ArrayList<>(edgeWeights.size());
        edgeWeights.forEach((key, w) -> {
            int sep = key.indexOf(' ');
            edges.add(new Edge(key.substring(0, sep), key.substring(sep + 1), w));
        });

        return new PackageGraphModel(nodes, edges,
            classModel.multiProject(), classModel.lombokAnnotatedTypes());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String pkgKey(String fqn) {
        String p = Fqns.packageOf(fqn);
        return p.isEmpty() ? "(default)" : p;
    }

    /** Returns the last two dot-separated segments as the display label. */
    static String shortLabel(String pkg) {
        if (pkg.equals("(default)")) return "(default)";
        int last  = pkg.lastIndexOf('.');
        if (last < 0) return pkg;
        int prev  = pkg.lastIndexOf('.', last - 1);
        return prev < 0 ? pkg : pkg.substring(prev + 1);
    }

    private static final class PkgAcc {
        final String label;
        final String project;
        int classes;
        boolean transactional;
        boolean external;
        boolean cyclic;
        boolean inRisk;
        boolean inLockRisk;
        final Map<String, Integer> projectCounts = new LinkedHashMap<>();

        PkgAcc(String label, String project) {
            this.label   = label;
            this.project = project;
        }
    }
}
