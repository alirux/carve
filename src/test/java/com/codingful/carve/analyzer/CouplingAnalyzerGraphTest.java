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

import com.codingful.carve.analyzer.CouplingAnalyzer.Archetype;
import com.codingful.carve.analyzer.CouplingAnalyzer.CouplingHotspots;
import com.codingful.carve.analyzer.CouplingAnalyzer.PackageCoupling;
import com.codingful.carve.analyzer.CouplingAnalyzer.PackageHotspot;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises the graph-driven parts of {@link CouplingAnalyzer} with real
 * {@link CallGraph} instances (SCC detection and package-coupling aggregation),
 * complementing the pure classification tests in {@code CouplingAnalyzerTest}.
 */
class CouplingAnalyzerGraphTest {

    private static MethodNode appIn(String pkg, String id) {
        return app(id).pkg(pkg).build();
    }

    // -----------------------------------------------------------------------
    // SCC — findCyclicClusters
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_mutual_cycle_WHEN_finding_cyclic_clusters_THEN_it_is_detected() {
        CallGraph g = new CallGraph();
        MethodNode a = appIn("p", "a");
        MethodNode b = appIn("p", "b");
        g.addEdge(a, b);
        g.addEdge(b, a); // a ⇄ b is a 2-node SCC

        List<Set<MethodNode>> clusters = new CouplingAnalyzer(g).findCyclicClusters();

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void GIVEN_an_acyclic_graph_WHEN_finding_cyclic_clusters_THEN_none() {
        CallGraph g = new CallGraph();
        g.addEdge(appIn("p", "a"), appIn("p", "b"));
        assertThat(new CouplingAnalyzer(g).findCyclicClusters()).isEmpty();
    }

    @Test
    void GIVEN_multiple_cycles_WHEN_finding_cyclic_clusters_THEN_sorted_by_size_descending() {
        CallGraph g = new CallGraph();
        // Small cycle: x ⇄ y
        MethodNode x = appIn("p", "x");
        MethodNode y = appIn("p", "y");
        g.addEdge(x, y);
        g.addEdge(y, x);
        // Larger cycle: a → b → c → a
        MethodNode a = appIn("q", "a");
        MethodNode b = appIn("q", "b");
        MethodNode c = appIn("q", "c");
        g.addEdge(a, b);
        g.addEdge(b, c);
        g.addEdge(c, a);

        List<Set<MethodNode>> clusters = new CouplingAnalyzer(g).findCyclicClusters();

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0)).hasSize(3); // largest first
        assertThat(clusters.get(1)).hasSize(2);
    }

    @Test
    void GIVEN_a_cycle_through_a_library_node_WHEN_finding_cyclic_clusters_THEN_it_is_excluded() {
        // A cycle that passes through a library node is not an application cycle.
        CallGraph g = new CallGraph();
        MethodNode a = appIn("p", "a");
        MethodNode libNode = lib("b").pkg("java.util").build();
        g.addEdge(a, libNode);
        g.addEdge(libNode, a);

        assertThat(new CouplingAnalyzer(g).findCyclicClusters()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Package coupling aggregation
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_cross_package_edges_WHEN_analysing_package_coupling_THEN_they_are_counted() {
        CallGraph g = new CallGraph();
        // app.web → app.svc and app.web → app.dao
        g.addEdge(appIn("app.web", "w"), appIn("app.svc", "s"));
        g.addEdge(appIn("app.web", "w2"), appIn("app.dao", "d"));

        Map<String, PackageCoupling> coupling = new CouplingAnalyzer(g).analysePackageCoupling();

        PackageCoupling web = coupling.get("app.web");
        assertThat(web.ce()).isEqualTo(2);            // depends on svc + dao
        assertThat(web.ca()).isZero();
        assertThat(web.applicationCode()).isTrue();

        assertThat(coupling.get("app.svc").ca()).isEqualTo(1); // web depends on it
        assertThat(coupling.get("app.svc").ce()).isZero();
    }

    @Test
    void GIVEN_intra_package_edges_WHEN_analysing_package_coupling_THEN_they_are_ignored() {
        CallGraph g = new CallGraph();
        g.addEdge(appIn("app.svc", "a"), appIn("app.svc", "b")); // same package

        PackageCoupling svc = new CouplingAnalyzer(g).analysePackageCoupling().get("app.svc");

        assertThat(svc.ce()).isZero();
        assertThat(svc.ca()).isZero();
        assertThat(svc.applicationCode()).isTrue(); // still recorded as application code
    }

    @Test
    void GIVEN_an_edge_from_a_library_source_WHEN_analysing_package_coupling_THEN_it_is_skipped() {
        CallGraph g = new CallGraph();
        // Source is library code → the whole edge is ignored.
        g.addEdge(lib("l").pkg("java.util").build(), appIn("app.svc", "s"));

        assertThat(new CouplingAnalyzer(g).analysePackageCoupling()).isEmpty();
    }

    @Test
    void GIVEN_an_edge_to_a_library_target_WHEN_analysing_package_coupling_THEN_target_tagged_non_application_code() {
        CallGraph g = new CallGraph();
        g.addEdge(appIn("app.svc", "s"), lib("l").pkg("java.util").build());

        Map<String, PackageCoupling> coupling = new CouplingAnalyzer(g).analysePackageCoupling();

        assertThat(coupling.get("app.svc").applicationCode()).isTrue();
        assertThat(coupling.get("java.util").applicationCode()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Inferred edges — ambiguous ones excluded from the coupling metrics
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_ambiguous_inferred_edge_WHEN_analysing_package_coupling_THEN_it_is_excluded_but_the_exact_one_is_kept() {
        CallGraph g = new CallGraph();
        MethodNode w = appIn("app.web", "w");
        g.addEdge(w, appIn("app.svc", "s"));           // observed at a call site
        g.addChaEdge(w, appIn("app.dao", "d"), 1);     // exactly resolved — a real dependency
        g.addChaEdge(w, appIn("app.ext", "e"), 2);     // one of two guesses — a phantom

        Map<String, PackageCoupling> coupling = new CouplingAnalyzer(g).analysePackageCoupling();

        // svc + dao are counted; the ambiguous ext edge is dropped, so ext never appears.
        assertThat(coupling.get("app.web").ce()).isEqualTo(2);
        assertThat(coupling.get("app.web").efferentDependencies())
            .containsExactlyInAnyOrder("app.svc", "app.dao");
        assertThat(coupling).doesNotContainKey("app.ext");
        assertThat(coupling.get("app.dao").ca()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Presentation-only packages — flagged so they can be kept off the
    // extraction-candidate list
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_package_of_only_controllers_WHEN_analysing_package_coupling_THEN_it_is_flagged_presentation_only() {
        CallGraph g = new CallGraph();
        MethodNode ctrl = app("c").pkg("app.web").type(SpringComponentType.REST_CONTROLLER).build();
        MethodNode svc  = app("s").pkg("app.svc").type(SpringComponentType.SERVICE).build();
        g.addEdge(ctrl, svc);

        Map<String, PackageCoupling> coupling = new CouplingAnalyzer(g).analysePackageCoupling();

        assertThat(coupling.get("app.web").presentationOnly()).isTrue();
        assertThat(coupling.get("app.svc").presentationOnly()).isFalse();
    }

    @Test
    void GIVEN_a_package_mixing_a_controller_and_a_service_WHEN_analysing_package_coupling_THEN_it_is_not_presentation_only() {
        CallGraph g = new CallGraph();
        MethodNode ctrl = app("c").pkg("app.web").type(SpringComponentType.CONTROLLER).build();
        MethodNode svc  = app("s").pkg("app.web").type(SpringComponentType.SERVICE).build();
        MethodNode dao  = app("d").pkg("app.dao").type(SpringComponentType.REPOSITORY).build();
        g.addEdge(ctrl, dao);
        g.addEdge(svc, dao);

        assertThat(new CouplingAnalyzer(g).analysePackageCoupling().get("app.web").presentationOnly())
            .isFalse();
    }

    @Test
    void GIVEN_a_controller_package_with_untyped_helpers_WHEN_analysing_package_coupling_THEN_still_presentation_only() {
        // A DTO or plain helper (no Spring stereotype) does not disqualify the package.
        CallGraph g = new CallGraph();
        MethodNode ctrl = app("c").pkg("app.web").type(SpringComponentType.REST_CONTROLLER).build();
        MethodNode dto  = app("dto").pkg("app.web").build(); // SpringComponentType.NONE
        MethodNode svc  = app("s").pkg("app.svc").type(SpringComponentType.SERVICE).build();
        g.addEdge(ctrl, svc);
        g.addEdge(ctrl, dto);

        assertThat(new CouplingAnalyzer(g).analysePackageCoupling().get("app.web").presentationOnly())
            .isTrue();
    }

    // -----------------------------------------------------------------------
    // PackageCoupling value semantics
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_package_with_no_coupling_WHEN_computing_instability_THEN_zero() {
        PackageCoupling isolated = new PackageCoupling("p", Set.of(), Set.of(), true);
        assertThat(isolated.instability()).isEqualTo(0.0);
    }

    @Test
    void GIVEN_afferent_and_efferent_deps_WHEN_computing_instability_THEN_ce_over_total() {
        PackageCoupling p = new PackageCoupling("p", Set.of("x", "y", "z"), Set.of("a"), true);
        assertThat(p.instability()).isCloseTo(0.75, within(1e-9)); // 3 / (1 + 3)
    }

    // -----------------------------------------------------------------------
    // CouplingHotspots aggregation views
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_hotspots_of_every_archetype_WHEN_aggregating_all_and_by_package_THEN_every_archetype_included() {
        CouplingHotspots h = CouplingAnalyzer.classifyHotspots(List.of(
            withCa("app.bo", 15, 53),    // unstable hub
            withCa("app.synch", 2, 34),  // extraction candidate
            withCa("app.dao", 17, 7)     // stable core
        ));

        List<String> all = h.all().stream().map(PackageHotspot::packageName).toList();
        assertThat(all).contains("app.bo", "app.synch", "app.dao");

        Map<String, PackageHotspot> byPkg = h.byPackage();
        assertThat(byPkg.get("app.bo").archetype()).isEqualTo(Archetype.UNSTABLE_HUB);
        assertThat(byPkg.get("app.synch").archetype()).isEqualTo(Archetype.EXTRACTION_CANDIDATE);
        assertThat(byPkg.get("app.dao").archetype()).isEqualTo(Archetype.STABLE_CORE);
    }

    // Helpers building a PackageCoupling with exact Ca/Ce counts.
    private static PackageCoupling withCa(String name, int ca, int ce) {
        return new PackageCoupling(name, namesCe(ce), namesCa(ca), true);
    }

    private static Set<String> namesCa(int n) { return names("ca", n); }
    private static Set<String> namesCe(int n) { return names("ce", n); }

    private static Set<String> names(String prefix, int n) {
        return java.util.stream.IntStream.range(0, n)
            .mapToObj(i -> prefix + i)
            .collect(java.util.stream.Collectors.toSet());
    }
}
