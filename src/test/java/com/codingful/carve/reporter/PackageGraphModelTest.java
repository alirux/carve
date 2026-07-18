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

import com.codingful.carve.analyzer.CouplingAnalyzer.Archetype;
import com.codingful.carve.analyzer.CouplingAnalyzer.PackageHotspot;
import com.codingful.carve.analyzer.LockRiskAnalyzer.NestedTxRisk;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.reporter.PackageGraphModel.Edge;
import com.codingful.carve.reporter.PackageGraphModel.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

class PackageGraphModelTest {

    // Package names reused as both graph input and lookup key: define once so the
    // two can never drift apart.
    private static final String DEFAULT_PKG = "(default)";
    private static final String APP      = "app";
    private static final String WEB      = "app.web";
    private static final String SVC      = "app.svc";
    private static final String BO       = "app.bo";
    private static final String ACME_WEB = "com.acme.web";
    private static final String API      = "api";
    private static final String CORE     = "core";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builder for an application method {@code <pkg>.<cls>#<id>} declared in {@code <pkg>.<cls>}. */
    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static ClassGraphModel classModel(CallGraph cg) {
        return ClassGraphModel.collapse(cg, List.of(), List.of(), List.of(), List.of());
    }

    private static PackageGraphModel collapse(CallGraph cg) {
        return PackageGraphModel.collapse(classModel(cg));
    }

    private static Node node(PackageGraphModel model, String pkg) {
        return model.nodes().stream()
            .filter(n -> n.id().equals(pkg))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no node: " + pkg));
    }

    // -----------------------------------------------------------------------
    // shortLabel — last two package segments
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_package_names_WHEN_building_the_short_label_THEN_last_two_segments_are_used() {
        assertThat(PackageGraphModel.shortLabel(DEFAULT_PKG)).isEqualTo(DEFAULT_PKG);
        assertThat(PackageGraphModel.shortLabel(APP)).isEqualTo(APP);
        assertThat(PackageGraphModel.shortLabel(WEB)).isEqualTo(WEB);
        assertThat(PackageGraphModel.shortLabel(ACME_WEB)).isEqualTo("acme.web");
    }

    // -----------------------------------------------------------------------
    // Class → package collapse
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_classes_of_the_same_package_WHEN_collapsing_THEN_one_node_counts_them() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(ACME_WEB, "A", "m").build());
        cg.addVertex(method(ACME_WEB, "B", "m").build());

        PackageGraphModel model = collapse(cg);

        assertThat(model.nodes()).hasSize(1);
        Node n = node(model, ACME_WEB);
        assertThat(n.label()).isEqualTo("acme.web");
        assertThat(n.classes()).isEqualTo(2);
    }

    @Test
    void GIVEN_a_default_package_class_WHEN_collapsing_THEN_node_is_the_default_package() {
        MethodNode top = new MethodNode("Top#m", "", "Top", "m", "Top", true);
        CallGraph cg = new CallGraph();
        cg.addVertex(top);

        assertThat(collapse(cg).nodes()).extracting(Node::id).containsExactly(DEFAULT_PKG);
    }

    @Test
    void GIVEN_classes_with_mixed_attributes_WHEN_collapsing_THEN_package_flags_are_ored() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(APP, "A", "m").transactional().build());
        cg.addVertex(method(APP, "B", "m").calls(ExternalCallType.HTTP).build());

        Node n = node(collapse(cg), APP);

        assertThat(n.transactional()).isTrue();
        assertThat(n.external()).isTrue();
    }

    @Test
    void GIVEN_classes_on_risk_paths_WHEN_collapsing_THEN_risk_flags_propagate_to_the_package() {
        MethodNode a = method(APP, "A", "m").build();
        MethodNode b = method(APP, "B", "n").build();
        CallGraph cg = new CallGraph();
        cg.addVertex(a);
        cg.addVertex(b);

        var risk    = new TransactionRisk(a, a, Set.of(ExternalCallType.HTTP), List.of(a));
        var cluster = Set.of(a, b);
        var nested  = new NestedTxRisk(a, a, List.of(a));
        ClassGraphModel cm = ClassGraphModel.collapse(
            cg, List.of(risk), List.of(cluster), List.of(nested), List.of());

        Node n = node(PackageGraphModel.collapse(cm), APP);
        assertThat(n.inRisk()).isTrue();
        assertThat(n.cyclic()).isTrue();
        assertThat(n.inLockRisk()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Dominant project
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_package_split_across_projects_WHEN_collapsing_THEN_the_dominant_project_wins() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(APP, "A", "m").project(API).build());
        cg.addVertex(method(APP, "B", "m").project(API).build());
        cg.addVertex(method(APP, "C", "m").project(CORE).build());

        assertThat(node(collapse(cg), APP).project()).isEqualTo(API); // 2 api vs 1 core
    }

    // -----------------------------------------------------------------------
    // Edges
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_cross_package_class_edges_WHEN_collapsing_THEN_a_weighted_package_edge_is_produced() {
        MethodNode a1 = method(WEB, "A", "1").build();
        MethodNode a2 = method(WEB, "A", "2").build();
        MethodNode b1 = method(SVC, "B", "1").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a1, b1);
        cg.addEdge(a2, b1); // class A→B weight 2

        List<Edge> edges = collapse(cg).edges();

        assertThat(edges).hasSize(1);
        Edge e = edges.get(0);
        assertThat(e.source()).isEqualTo(WEB);
        assertThat(e.target()).isEqualTo(SVC);
        assertThat(e.weight()).isEqualTo(2);
    }

    @Test
    void GIVEN_several_class_edges_between_two_packages_WHEN_collapsing_THEN_weights_are_summed() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method(WEB, "A", "1").build(), method(SVC, "B", "1").build());
        cg.addEdge(method(WEB, "C", "1").build(), method(SVC, "D", "1").build());

        assertThat(collapse(cg).edges()).singleElement()
            .satisfies(e -> {
                assertThat(e.source()).isEqualTo(WEB);
                assertThat(e.target()).isEqualTo(SVC);
                assertThat(e.weight()).isEqualTo(2);
            });
    }

    @Test
    void GIVEN_a_package_coupling_resting_only_on_cha_WHEN_collapsing_THEN_the_edge_is_marked_cha() {
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method(WEB, "A", "1").build(), method(SVC, "B", "1").build());

        assertThat(collapse(cg).edges()).singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo("cha");
                assertThat(e.chaWeight()).isEqualTo(1);
            });
    }

    @Test
    void GIVEN_a_package_coupling_with_one_direct_class_edge_WHEN_collapsing_THEN_the_edge_is_direct() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method(WEB, "A", "1").build(), method(SVC, "B", "1").build());
        cg.addChaEdge(method(WEB, "C", "1").build(), method(SVC, "D", "1").build());

        assertThat(collapse(cg).edges()).singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo("direct");
                assertThat(e.weight()).isEqualTo(2);
                assertThat(e.chaWeight()).isEqualTo(1);
            });
    }

    @Test
    void GIVEN_an_intra_package_class_edge_WHEN_collapsing_THEN_no_package_edge_is_produced() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method(APP, "A", "1").build(), method(APP, "B", "1").build());

        assertThat(collapse(cg).edges()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Multi-project & hotspot tagging
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_classes_from_two_projects_WHEN_collapsing_THEN_multi_project_is_true() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(API + ".web", "A", "m").project(API).build());
        cg.addVertex(method(CORE + ".svc", "B", "m").project(CORE).build());

        assertThat(collapse(cg).multiProject()).isTrue();
    }

    @Test
    void GIVEN_a_hotspot_for_a_package_WHEN_collapsing_THEN_the_node_carries_its_archetype_and_score() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(BO, "A", "m").build());
        var hotspot = new PackageHotspot(BO, Archetype.UNSTABLE_HUB, 5, 12, 0.71, 8.5);

        PackageGraphModel model =
            PackageGraphModel.collapse(classModel(cg), Map.of(BO, hotspot));

        // Derive the expected values from the input hotspot rather than repeating
        // its serialized form, so the assertion stays tied to its source of truth.
        Node n = node(model, BO);
        assertThat(n.archetype()).isEqualTo(hotspot.archetype().jsonKey());
        assertThat(n.score()).isEqualTo(hotspot.score());
    }

    @Test
    void GIVEN_no_hotspot_for_a_package_WHEN_collapsing_THEN_archetype_is_null_and_score_zero() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method(APP, "A", "m").build());

        Node n = node(collapse(cg), APP);
        assertThat(n.archetype()).isNull();
        assertThat(n.score()).isZero();
    }
}
