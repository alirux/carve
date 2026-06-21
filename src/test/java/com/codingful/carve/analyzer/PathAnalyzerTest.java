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

import com.codingful.carve.analyzer.PathAnalyzer.LongestPath;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

class PathAnalyzerTest {

    private static List<String> ids(LongestPath path) {
        return path.nodes().stream().map(MethodNode::getId).toList();
    }

    // -----------------------------------------------------------------------
    // Degenerate graphs
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_empty_graph_WHEN_finding_longest_paths_THEN_none() {
        assertThat(new PathAnalyzer(new CallGraph()).findLongestPaths(5)).isEmpty();
    }

    @Test
    void GIVEN_isolated_nodes_with_no_edges_WHEN_finding_longest_paths_THEN_none() {
        // No edges → every reconstructed path has length < 2 and is discarded.
        CallGraph g = new CallGraph();
        g.addVertex(app("a").build());
        g.addVertex(app("b").build());
        assertThat(new PathAnalyzer(g).findLongestPaths(5)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Linear chains
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_linear_chain_WHEN_finding_longest_paths_THEN_returned_longest_first() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = app("c").build();
        MethodNode d = app("d").build();
        g.addEdge(a, b);
        g.addEdge(b, c);
        g.addEdge(c, d);

        List<LongestPath> paths = new PathAnalyzer(g).findLongestPaths(10);

        assertThat(paths).isNotEmpty();
        LongestPath longest = paths.get(0);
        assertThat(ids(longest)).containsExactly("a", "b", "c", "d");
        assertThat(longest.depth()).isEqualTo(4);
        // Results are ordered by descending depth.
        assertThat(paths).isSortedAccordingTo((p1, p2) -> Integer.compare(p2.depth(), p1.depth()));
    }

    @Test
    void GIVEN_a_top_n_limit_WHEN_finding_longest_paths_THEN_number_of_paths_is_capped() {
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = app("c").build();
        g.addEdge(a, b);
        g.addEdge(b, c);

        assertThat(new PathAnalyzer(g).findLongestPaths(1)).hasSize(1);
    }

    @Test
    void GIVEN_a_branching_graph_WHEN_finding_longest_paths_THEN_the_deeper_branch_is_followed() {
        // a → b (leaf) and a → c → d : the greedy reconstruction should prefer c.
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = app("c").build();
        MethodNode d = app("d").build();
        g.addEdge(a, b);
        g.addEdge(a, c);
        g.addEdge(c, d);

        LongestPath longest = new PathAnalyzer(g).findLongestPaths(10).get(0);
        assertThat(ids(longest)).containsExactly("a", "c", "d");
    }

    // -----------------------------------------------------------------------
    // Library exclusion & cycles
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_library_node_on_the_path_WHEN_finding_longest_paths_THEN_path_stops_at_application_boundary() {
        // a → b (app) → c (library): the path stops at the application boundary.
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = lib("c").build();
        g.addEdge(a, b);
        g.addEdge(b, c);

        LongestPath longest = new PathAnalyzer(g).findLongestPaths(10).get(0);
        assertThat(ids(longest)).containsExactly("a", "b");
    }

    @Test
    void GIVEN_a_cycle_WHEN_finding_longest_paths_THEN_terminates_with_a_simple_path() {
        // a → b → c → a : must not loop forever, and the path stays simple.
        CallGraph g = new CallGraph();
        MethodNode a = app("a").build();
        MethodNode b = app("b").build();
        MethodNode c = app("c").build();
        g.addEdge(a, b);
        g.addEdge(b, c);
        g.addEdge(c, a);

        List<LongestPath> paths = new PathAnalyzer(g).findLongestPaths(10);

        assertThat(paths).isNotEmpty();
        LongestPath longest = paths.get(0);
        assertThat(longest.depth()).isGreaterThanOrEqualTo(2);
        // A simple path never repeats a node.
        assertThat(ids(longest)).doesNotHaveDuplicates();
    }

    // -----------------------------------------------------------------------
    // External-call flag on a path
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_path_with_or_without_io_WHEN_checking_has_external_calls_THEN_reflects_nodes_on_the_path() {
        CallGraph withIo = new CallGraph();
        withIo.addEdge(app("a").build(), app("b").calls(ExternalCallType.HTTP).build());
        assertThat(new PathAnalyzer(withIo).findLongestPaths(1).get(0).hasExternalCalls()).isTrue();

        CallGraph pureCompute = new CallGraph();
        pureCompute.addEdge(app("a").build(), app("b").build());
        assertThat(new PathAnalyzer(pureCompute).findLongestPaths(1).get(0).hasExternalCalls()).isFalse();
    }

    @Test
    void GIVEN_a_null_graph_WHEN_constructing_THEN_throws() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> new PathAnalyzer(null))
            .isInstanceOf(NullPointerException.class);
    }
}
