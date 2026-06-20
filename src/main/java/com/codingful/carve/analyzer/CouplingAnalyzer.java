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
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Identifies clusters of application code that are tightly coupled.
 *
 * <h3>Strategies</h3>
 * <ol>
 *   <li><b>Strongly Connected Components (SCC)</b> — groups of methods with
 *       mutual (direct or indirect) call cycles. An SCC with more than one
 *       vertex represents code that cannot be split without breaking cycles.</li>
 *   <li><b>Package coupling</b> — counts cross-package dependency edges to
 *       surface packages with high afferent/efferent coupling.</li>
 * </ol>
 */
public class CouplingAnalyzer {

    private final CallGraph callGraph;

    public CouplingAnalyzer(CallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    // -----------------------------------------------------------------------
    // SCC — cycle detection
    // -----------------------------------------------------------------------

    /**
     * Returns all Strongly Connected Components with more than one method
     * (i.e., actual cycles), sorted descending by size.
     *
     * Only application nodes are considered; library stubs are excluded.
     */
    public List<Set<MethodNode>> findCyclicClusters() {
        // Build a sub-graph containing only application-code nodes
        Set<MethodNode> appNodes = callGraph.applicationNodes();
        var subgraph = new AsSubgraph<>(callGraph.getRaw(), appNodes);

        var inspector = new KosarajuStrongConnectivityInspector<>(subgraph);
        return inspector.stronglyConnectedSets().stream()
            .filter(scc -> scc.size() > 1)
            .sorted(Comparator.<Set<MethodNode>>comparingInt(Set::size).reversed())
            .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Package coupling
    // -----------------------------------------------------------------------

    /**
     * Returns a map of package name → {@link PackageCoupling}.
     * Edges between two methods in the same package are treated as internal;
     * edges crossing a package boundary count toward efferent (Ce) coupling.
     */
    public Map<String, PackageCoupling> analysePackageCoupling() {
        Map<String, PackageCoupling.Builder> builders = new HashMap<>();

        for (DefaultEdge edge : callGraph.edges()) {
            MethodNode source = callGraph.getRaw().getEdgeSource(edge);
            MethodNode target = callGraph.getRaw().getEdgeTarget(edge);

            if (!source.isApplicationCode()) continue;

            String srcPkg = source.getPackageName();
            String tgtPkg = target.getPackageName();

            builders.computeIfAbsent(srcPkg, PackageCoupling.Builder::new);

            if (!srcPkg.equals(tgtPkg)) {
                // srcPkg depends on tgtPkg → efferent for src, afferent for tgt
                builders.get(srcPkg).addEfferent(tgtPkg);
                builders.computeIfAbsent(tgtPkg, PackageCoupling.Builder::new)
                        .addAfferent(srcPkg);
            }
        }

        return builders.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().build()));
    }

    // -----------------------------------------------------------------------
    // Value objects
    // -----------------------------------------------------------------------

    public record PackageCoupling(
        String packageName,
        /** Packages that this package depends on (outgoing). */
        Set<String> efferentDependencies,
        /** Packages that depend on this package (incoming). */
        Set<String> afferentDependencies
    ) {
        /** Ce — efferent coupling count */
        public int ce() { return efferentDependencies.size(); }
        /** Ca — afferent coupling count */
        public int ca() { return afferentDependencies.size(); }

        /**
         * Instability I = Ce / (Ca + Ce).
         * 0 = maximally stable (many dependents, no outgoing deps).
         * 1 = maximally unstable (no dependents, all outgoing deps).
         */
        public double instability() {
            int total = ca() + ce();
            return total == 0 ? 0.0 : (double) ce() / total;
        }

        static class Builder {
            private final String pkg;
            private final Set<String> efferent = new HashSet<>();
            private final Set<String> afferent  = new HashSet<>();

            Builder(String pkg) { this.pkg = pkg; }

            Builder addEfferent(String p) { efferent.add(p); return this; }
            Builder addAfferent(String p) { afferent.add(p); return this; }

            PackageCoupling build() {
                return new PackageCoupling(pkg, Set.copyOf(efferent), Set.copyOf(afferent));
            }
        }
    }
}
