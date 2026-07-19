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
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.TransactionPropagation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

class DotReporterTest {

    // DOT attribute names (the contract), declared once.
    private static final String ATTR_LABEL       = "label";
    private static final String ATTR_FILLCOLOR   = "fillcolor";
    private static final String ATTR_TOOLTIP     = "tooltip";
    private static final String ATTR_PERIPHERIES = "peripheries";
    private static final String ATTR_COLOR       = "color";

    // Role colours.
    private static final String C_LIBRARY = "lightgrey";
    private static final String C_TX_EXT  = "orange";
    private static final String C_TX      = "lightyellow";
    private static final String C_EXTERNAL = "lightcoral";
    private static final String C_PLAIN   = "white";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static String write(DotReporter reporter, boolean onlyApplicationCode) {
        StringWriter w = new StringWriter();
        reporter.write(w, onlyApplicationCode);
        return w.toString();
    }

    private static String writeWithRisks(DotReporter reporter, boolean onlyApplicationCode,
                                         List<TransactionRisk> risks) {
        StringWriter w = new StringWriter();
        reporter.writeWithRisks(w, onlyApplicationCode, risks);
        return w.toString();
    }

    private static String writeWithRisks(DotReporter reporter, List<TransactionRisk> risks) {
        return writeWithRisks(reporter, false, risks);
    }

    /** The single graphviz line that declares the vertex with the given id. */
    private static String vertexLine(String dot, String id) {
        String needle = "\"" + id + "\" [";
        return dot.lines().filter(l -> l.contains(needle))
            .findFirst().orElseThrow(() -> new AssertionError("no vertex line for: " + id));
    }

    /** The single graphviz line for the edge from {@code src} to {@code tgt}. */
    private static String edgeLine(String dot, String src, String tgt) {
        String needle = "\"" + src + "\" -> \"" + tgt + "\"";
        return dot.lines().filter(l -> l.contains(needle))
            .findFirst().orElseThrow(() -> new AssertionError("no edge line: " + src + "->" + tgt));
    }

    /** The {@code name="value"} fragment as it appears in DOT. */
    private static String attr(String name, String value) {
        return name + "=\"" + value + "\"";
    }

    // -----------------------------------------------------------------------
    // Skeleton & nodes
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_graph_WHEN_writing_dot_THEN_output_is_a_digraph_with_layout_attributes() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        String dot = write(new DotReporter(cg), false);

        assertThat(dot).contains("digraph");
        assertThat(dot).contains("rankdir=LR"); // graph attributes are written unquoted
    }

    @Test
    void GIVEN_a_graph_WHEN_writing_dot_THEN_first_line_is_the_copyright_comment() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        String dot = write(new DotReporter(cg), false);

        assertThat(dot).startsWith(ReportMetadata.asLineComment());
    }

    @Test
    void GIVEN_a_node_WHEN_writing_dot_THEN_id_is_quoted_and_label_has_class_and_method() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "run").build());

        String line = vertexLine(write(new DotReporter(cg), false), "app.Svc#run");

        // label = "<class>\n<method>" (literal backslash-n separator)
        assertThat(line).contains(attr(ATTR_LABEL, "Svc\\nrun"));
    }

    @Test
    void GIVEN_class_role_variants_WHEN_writing_dot_THEN_fillcolor_matches_the_role() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "TxExt", "m").transactional().calls(ExternalCallType.HTTP).build());
        cg.addVertex(method("app", "Tx", "m").transactional().build());
        cg.addVertex(method("app", "Ext", "m").calls(ExternalCallType.HTTP).build());
        cg.addVertex(method("app", "Plain", "m").build());
        cg.addVertex(lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build());

        String dot = write(new DotReporter(cg), false);

        assertThat(vertexLine(dot, "app.TxExt#m")).contains(attr(ATTR_FILLCOLOR, C_TX_EXT));
        assertThat(vertexLine(dot, "app.Tx#m")).contains(attr(ATTR_FILLCOLOR, C_TX));
        assertThat(vertexLine(dot, "app.Ext#m")).contains(attr(ATTR_FILLCOLOR, C_EXTERNAL));
        assertThat(vertexLine(dot, "app.Plain#m")).contains(attr(ATTR_FILLCOLOR, C_PLAIN));
        assertThat(vertexLine(dot, "ext.Lib#x")).contains(attr(ATTR_FILLCOLOR, C_LIBRARY));
    }

    @Test
    void GIVEN_a_cyclic_node_WHEN_writing_dot_THEN_it_gets_double_peripheries() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode b = method("app", "B", "m").build();
        CallGraph cg = new CallGraph();
        cg.addVertex(a);
        cg.addVertex(b);

        String dot = write(new DotReporter(cg, List.of(Set.of(a))), false);

        assertThat(vertexLine(dot, "app.A#m")).contains(attr(ATTR_PERIPHERIES, "2"));
        assertThat(vertexLine(dot, "app.B#m")).doesNotContain(ATTR_PERIPHERIES);
    }

    @Test
    void GIVEN_a_transactional_node_WHEN_writing_dot_THEN_tooltip_shows_propagation_and_read_only() {
        MethodNode tx = method("app", "Svc", "m").transactional().build();
        tx.setPropagation(TransactionPropagation.REQUIRES_NEW);
        tx.setReadOnly(true);
        CallGraph cg = new CallGraph();
        cg.addVertex(tx);

        assertThat(vertexLine(write(new DotReporter(cg), false), "app.Svc#m"))
            .contains(attr(ATTR_TOOLTIP, "TX:REQUIRES_NEW readOnly"));
    }

    @Test
    void GIVEN_a_multi_project_node_WHEN_writing_dot_THEN_label_is_project_prefixed() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("api", "A", "m").project("api").build());
        cg.addVertex(method("core", "B", "m").project("core").build()); // makes it multi-project
        cg.addVertex(method("app", "NoProj", "m").build());            // no project name

        String dot = write(new DotReporter(cg), false);

        assertThat(vertexLine(dot, "api.A#m")).contains(attr(ATTR_LABEL, "[api]\\nA\\nm"));
        // A node without a project gets no prefix, even in multi-project mode.
        assertThat(vertexLine(dot, "app.NoProj#m")).contains(attr(ATTR_LABEL, "NoProj\\nm"));
    }

    // -----------------------------------------------------------------------
    // Edges, filtering, risks, escaping
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_cross_node_calls_WHEN_writing_dot_THEN_edges_are_emitted() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "m").build(), method("app", "B", "m").build());

        assertThat(write(new DotReporter(cg), false))
            .contains("\"app.A#m\" -> \"app.B#m\"");
    }

    @Test
    void GIVEN_only_application_code_WHEN_writing_dot_THEN_library_nodes_are_omitted() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode libNode = lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a, libNode);

        assertThat(write(new DotReporter(cg), false)).contains("ext.Lib#x");   // full graph
        assertThat(write(new DotReporter(cg), true)).doesNotContain("ext.Lib#x"); // app-only
    }

    @Test
    void GIVEN_a_risk_path_WHEN_writing_dot_with_risks_THEN_only_risk_edges_are_red() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode b = method("app", "B", "m").build();
        MethodNode c = method("app", "C", "m").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a, b); // on the risk path
        cg.addEdge(b, c); // not on the risk path
        var risk = new TransactionRisk(a, b, Set.of(ExternalCallType.HTTP), List.of(a, b));

        String dot = writeWithRisks(new DotReporter(cg), List.of(risk));

        assertThat(edgeLine(dot, "app.A#m", "app.B#m")).contains(attr(ATTR_COLOR, "red"));
        assertThat(edgeLine(dot, "app.B#m", "app.C#m")).doesNotContain(attr(ATTR_COLOR, "red"));
    }

    @Test
    void GIVEN_application_only_with_risks_WHEN_writing_dot_THEN_library_omitted_and_risk_edge_red() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode b = method("app", "B", "m").build();
        MethodNode libNode = lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a, b);
        cg.addEdge(b, libNode);
        var risk = new TransactionRisk(a, b, Set.of(ExternalCallType.HTTP), List.of(a, b));

        String dot = writeWithRisks(new DotReporter(cg), true, List.of(risk));

        assertThat(dot).doesNotContain("ext.Lib#x");
        assertThat(edgeLine(dot, "app.A#m", "app.B#m")).contains(attr(ATTR_COLOR, "red"));
    }

    @Test
    void GIVEN_an_id_with_a_double_quote_WHEN_writing_dot_THEN_it_is_escaped() {
        MethodNode weird = new MethodNode("app.A#m(\"x\")", "app", "A", "m", "app.A", true);
        CallGraph cg = new CallGraph();
        cg.addVertex(weird);

        // The inner double quotes must be backslash-escaped in the DOT id.
        assertThat(write(new DotReporter(cg), false)).contains("app.A#m(\\\"x\\\")");
    }

    // -----------------------------------------------------------------------
    // Inferred edges
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_call_site_in_the_source_WHEN_writing_dot_THEN_the_edge_carries_no_style() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "m").build(), method("app", "B", "m").build());

        assertThat(edgeLine(write(new DotReporter(cg), false), "app.A#m", "app.B#m"))
            .doesNotContain("dashed")
            .doesNotContain(ATTR_COLOR + "=");
    }

    @Test
    void GIVEN_an_inference_over_one_implementation_WHEN_writing_dot_THEN_the_edge_is_dashed_grey() {
        // CHA had no choice to make, so the edge is exact: dashed to show it is not
        // a call site, but not flagged as an over-approximation.
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app", "A", "m").build(), method("app", "OnlyImpl", "m").build(), 1);

        String line = edgeLine(write(new DotReporter(cg), false), "app.A#m", "app.OnlyImpl#m");
        assertThat(line).contains(attr("style", "dashed"))
                        .contains(attr(ATTR_COLOR, "gray60"))
                        .contains("the only implementation");
    }

    @Test
    void GIVEN_an_inference_over_several_implementations_WHEN_writing_dot_THEN_the_edge_is_amber() {
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app", "A", "m").build(), method("app", "FirstImpl", "m").build(), 4);

        String line = edgeLine(write(new DotReporter(cg), false), "app.A#m", "app.FirstImpl#m");
        assertThat(line).contains(attr("style", "dashed"))
                        .contains(attr(ATTR_COLOR, "#c98a2e"))
                        .contains("one of 4 implementations");
    }

    @Test
    void GIVEN_an_ambiguous_inference_on_a_risk_path_WHEN_writing_dot_THEN_red_still_wins() {
        // A risk path is the more urgent signal, and the risk analysis deliberately
        // follows inferred edges, so the amber must not mask it.
        MethodNode a = method("app", "A", "m").build();
        MethodNode b = method("app", "B", "m").build();
        CallGraph cg = new CallGraph();
        cg.addChaEdge(a, b, 5);
        var risk = new TransactionRisk(a, b, Set.of(ExternalCallType.HTTP), List.of(a, b));

        String dot = writeWithRisks(new DotReporter(cg), List.of(risk));

        assertThat(edgeLine(dot, "app.A#m", "app.B#m"))
            .contains(attr(ATTR_COLOR, "red"))
            .doesNotContain("#c98a2e");
    }
}
