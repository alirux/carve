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
import org.junit.jupiter.api.Test;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

class CallGraphTest {

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_duplicate_vertex_WHEN_added_THEN_it_is_ignored() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        g.addVertex(a);
        g.addVertex(app("a").build()); // equal by id
        assertThat(g.vertexCount()).isEqualTo(1);
    }

    @Test
    void GIVEN_an_edge_with_unknown_vertices_WHEN_added_THEN_vertices_are_created() {
        CallGraph g = new CallGraph();
        g.addEdge(app("a").build(), app("b").build());
        assertThat(g.vertexCount()).isEqualTo(2);
        assertThat(g.edgeCount()).isEqualTo(1);
    }

    @Test
    void GIVEN_a_self_loop_WHEN_added_THEN_it_is_skipped() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        g.addEdge(a, app("a").build());
        assertThat(g.edgeCount()).isZero();
    }

    @Test
    void GIVEN_a_cha_edge_WHEN_added_THEN_it_is_tagged_as_inferred() {
        CallGraph g = new CallGraph();
        MethodNode caller = app("a").build();
        MethodNode impl   = app("b").build();

        assertThat(g.addChaEdge(caller, impl)).isTrue();

        assertThat(g.chaEdgeCount()).isEqualTo(1);
        assertThat(g.isChaEdge(g.edges().iterator().next())).isTrue();
    }

    @Test
    void GIVEN_an_edge_already_observed_at_a_call_site_WHEN_cha_rediscovers_it_THEN_it_stays_direct() {
        // Direct evidence is the stronger claim: CHA must not downgrade an edge
        // that a real call site already produced.
        CallGraph g = new CallGraph();
        MethodNode caller = app("a").build();
        MethodNode callee = app("b").build();
        g.addEdge(caller, callee);

        assertThat(g.addChaEdge(caller, callee)).isFalse();

        assertThat(g.edgeCount()).isEqualTo(1);
        assertThat(g.chaEdgeCount()).isZero();
        assertThat(g.isChaEdge(g.edges().iterator().next())).isFalse();
    }

    @Test
    void GIVEN_a_cha_self_loop_WHEN_added_THEN_it_is_skipped_and_not_tagged() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();

        assertThat(g.addChaEdge(a, app("a").build())).isFalse();

        assertThat(g.edgeCount()).isZero();
        assertThat(g.chaEdgeCount()).isZero();
    }

    @Test
    void GIVEN_only_direct_edges_WHEN_counting_cha_edges_THEN_the_count_is_zero() {
        CallGraph g = new CallGraph();
        g.addEdge(app("a").build(), app("b").build());
        assertThat(g.chaEdgeCount()).isZero();
        assertThat(g.isChaEdge(g.edges().iterator().next())).isFalse();
    }

    // -----------------------------------------------------------------------
    // Traversal
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_directed_graph_WHEN_querying_successors_and_predecessors_THEN_they_follow_edge_direction() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = app("c").build();
        g.addEdge(a, b);
        g.addEdge(a, c);
        g.addEdge(b, c);

        assertThat(g.successors(a)).containsExactlyInAnyOrder(b, c);
        assertThat(g.successors(c)).isEmpty();
        assertThat(g.predecessors(c)).containsExactlyInAnyOrder(a, b);
        assertThat(g.predecessors(a)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Filtered views
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_application_and_library_nodes_WHEN_filtering_application_nodes_THEN_library_stubs_are_excluded() {
        CallGraph g = new CallGraph();
        MethodNode appNode = app("a").build();
        g.addVertex(appNode);
        g.addVertex(lib("b").build());
        assertThat(g.applicationNodes()).containsExactly(appNode);
    }

    @Test
    void GIVEN_transactional_and_plain_nodes_WHEN_filtering_transactional_THEN_only_transactional_returned() {
        CallGraph g = new CallGraph();
        MethodNode tx = app("a").transactional().build();
        g.addVertex(tx);
        g.addVertex(app("b").build());
        assertThat(g.transactionalNodes()).containsExactly(tx);
    }

    @Test
    void GIVEN_nodes_with_different_external_calls_WHEN_filtering_by_type_THEN_only_matching_returned() {
        CallGraph g = new CallGraph();
        MethodNode http = app("a").calls(ExternalCallType.HTTP).build();
        MethodNode jpa  = app("b").calls(ExternalCallType.JPA).build();
        g.addVertex(http);
        g.addVertex(jpa);

        assertThat(g.nodesWithExternalCall(ExternalCallType.HTTP)).containsExactly(http);
        assertThat(g.nodesWithExternalCall(ExternalCallType.MESSAGING)).isEmpty();
    }

    @Test
    void GIVEN_nodes_of_different_component_types_WHEN_filtering_by_type_THEN_only_matching_returned() {
        CallGraph g = new CallGraph();
        MethodNode service = app("a").type(SpringComponentType.SERVICE).build();
        g.addVertex(service);
        g.addVertex(app("b").type(SpringComponentType.REPOSITORY).build());

        assertThat(g.nodesOfType(SpringComponentType.SERVICE)).containsExactly(service);
        assertThat(g.nodesOfType(SpringComponentType.CONTROLLER)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Multi-project detection
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_single_or_unnamed_projects_WHEN_checking_has_multiple_projects_THEN_false() {
        CallGraph g = new CallGraph();
        g.addVertex(app("a").build());                 // empty project name
        g.addVertex(app("b").project("api").build());  // one named project
        assertThat(g.hasMultipleProjects()).isFalse();
    }

    @Test
    void GIVEN_two_named_projects_WHEN_checking_has_multiple_projects_THEN_true() {
        CallGraph g = new CallGraph();
        g.addVertex(app("a").project("api").build());
        g.addVertex(app("b").project("worker").build());
        assertThat(g.hasMultipleProjects()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Counters / toString
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_graph_with_vertices_and_edges_WHEN_to_string_THEN_reports_counts() {
        CallGraph g = new CallGraph();
        g.addEdge(app("a").build(), app("b").build());
        assertThat(g).hasToString("CallGraph{vertices=2, edges=1}");
        assertThat(g.vertices()).hasSize(2);
        assertThat(g.edges()).hasSize(1);
        assertThat(g.getRaw().vertexSet()).hasSize(2);
    }

    @Test
    void GIVEN_the_same_pair_inferred_through_two_interfaces_WHEN_tagging_THEN_the_worst_fan_out_wins() {
        // A callee can implement two interfaces, so CHA reaches it twice with
        // different fan-outs. Keeping the first would make the value depend on the
        // iteration order of the implementor index, which is a HashMap.
        var caller = app("a.A#m").build();
        var callee = app("b.B#m").build();
        CallGraph g = new CallGraph();

        assertThat(g.addChaEdge(caller, callee, 1)).isTrue();
        assertThat(g.addChaEdge(caller, callee, 5)).isFalse();

        assertThat(g.chaFanOut(g.getRaw().getEdge(caller, callee))).isEqualTo(5);
    }

    @Test
    void GIVEN_the_worst_fan_out_arriving_first_WHEN_tagging_THEN_it_is_not_lowered() {
        var caller = app("a.A#m").build();
        var callee = app("b.B#m").build();
        CallGraph g = new CallGraph();

        g.addChaEdge(caller, callee, 5);
        g.addChaEdge(caller, callee, 1);

        assertThat(g.chaFanOut(g.getRaw().getEdge(caller, callee))).isEqualTo(5);
    }

    @Test
    void GIVEN_an_edge_observed_at_a_call_site_WHEN_cha_rediscovers_it_THEN_it_carries_no_fan_out() {
        // Direct evidence outranks inference, so the edge stays direct and the
        // fan-out stays absent rather than describing an inference not relied on.
        var caller = app("a.A#m").build();
        var callee = app("b.B#m").build();
        CallGraph g = new CallGraph();

        g.addEdge(caller, callee);
        g.addChaEdge(caller, callee, 4);

        var edge = g.getRaw().getEdge(caller, callee);
        assertThat(g.isChaEdge(edge)).isFalse();
        assertThat(g.chaFanOut(edge)).isZero();
    }
}
