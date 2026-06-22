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

import com.codingful.carve.analyzer.LockRiskAnalyzer.CyclicTxRisk;
import com.codingful.carve.analyzer.LockRiskAnalyzer.NestedTxRisk;
import com.codingful.carve.analyzer.PathAnalyzer.LongestPath;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.TransactionPropagation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what {@link ConsoleReporter} prints.
 *
 * <p>Console output is free-form text, so the strategy is to assert on stable,
 * meaningful anchors only: section headers (held once as constants — the format
 * contract), the empty-state marker, and the data that comes from the input
 * model (method labels, counts, call types). Decorative formatting — dividers,
 * column padding, tree connectors, arrows and emoji — is deliberately not pinned.
 */
class ConsoleReporterTest {

    // The format contract: header/marker fragments, declared once.
    private static final String RISKS     = "Transaction risks:";
    private static final String CYCLES    = "Cyclic clusters:";
    private static final String PATHS     = "Longest call chains:";
    private static final String NESTED    = "Lock risks — nested REQUIRES_NEW:";
    private static final String CYCLIC_TX = "Lock risks — cyclic @Transactional clusters:";
    private static final String TX        = "@Transactional";
    private static final String NONE      = "(none found)";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String capture(Consumer<ConsoleReporter> action) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        action.accept(new ConsoleReporter(new PrintStream(buf, true, StandardCharsets.UTF_8)));
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Application node whose console label renders as {@code Class.method()}. */
    private static MethodNode m(String className, String method) {
        return app(className + "." + method).className(className).method(method).build();
    }

    /** The label ConsoleReporter renders for a node (project prefix omitted). */
    private static String label(String className, String method) {
        return className + "." + method + "()";
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // -----------------------------------------------------------------------
    // Transaction risks
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_transaction_risks_WHEN_printing_THEN_header_shows_zero_and_none_marker() {
        String out = capture(r -> r.print(List.of()));
        assertThat(out).contains(RISKS + " 0").contains(NONE);
    }

    @Test
    void GIVEN_a_transaction_risk_WHEN_printing_THEN_header_path_labels_and_call_types_are_shown() {
        MethodNode root = app("OrderService.checkout")
            .className("OrderService").method("checkout").transactional().build();
        root.setReadOnly(true);
        // Middle node is transactional but NOT read-only — exercises that suffix variant.
        MethodNode mid = app("Svc.run").className("Svc").method("run").transactional().build();
        MethodNode site = app("PaymentClient.charge")
            .className("PaymentClient").method("charge").calls(ExternalCallType.HTTP).build();
        var risk = new TransactionRisk(root, site, Set.of(ExternalCallType.HTTP), List.of(root, mid, site));

        String out = capture(r -> r.print(List.of(risk)));

        assertThat(out).contains(RISKS + " 1");
        assertThat(out).contains("Risk 1/1");
        assertThat(out).contains("[HTTP]");
        assertThat(out).contains(label("OrderService", "checkout"));
        assertThat(out).contains(label("Svc", "run"));
        assertThat(out).contains(label("PaymentClient", "charge"));
        assertThat(out).contains(TX + "(readOnly)"); // root is transactional + read-only
    }

    @Test
    void GIVEN_several_transaction_risks_WHEN_printing_THEN_each_is_numbered_in_sequence() {
        MethodNode a = app("A.a").className("A").method("a").transactional().build();
        MethodNode b = app("B.b").className("B").method("b").calls(ExternalCallType.HTTP).build();
        var r1 = new TransactionRisk(a, b, Set.of(ExternalCallType.HTTP), List.of(a, b));
        var r2 = new TransactionRisk(a, b, Set.of(ExternalCallType.HTTP), List.of(a, b));

        String out = capture(r -> r.print(List.of(r1, r2)));

        assertThat(out).contains(RISKS + " 2");
        assertThat(out).contains("Risk 1/2");
        assertThat(out).contains("Risk 2/2");
    }

    @Test
    void GIVEN_a_risk_with_several_call_types_WHEN_printing_THEN_they_are_joined() {
        MethodNode root = app("S.m").className("S").method("m").transactional().build();
        MethodNode site = app("C.call").className("C").method("call").build();
        // EnumSet iterates in declaration order → deterministic "HTTP, JDBC".
        Set<ExternalCallType> types = EnumSet.of(ExternalCallType.HTTP, ExternalCallType.JDBC);
        var risk = new TransactionRisk(root, site, types, List.of(root, site));

        assertThat(capture(r -> r.print(List.of(risk)))).contains("[HTTP, JDBC]");
    }

    // -----------------------------------------------------------------------
    // Cyclic clusters
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_cyclic_clusters_WHEN_printing_THEN_header_shows_zero_and_none_marker() {
        String out = capture(r -> r.printCycles(List.of()));
        assertThat(out).contains(CYCLES + " 0").contains(NONE);
    }

    @Test
    void GIVEN_a_cyclic_cluster_WHEN_printing_THEN_header_count_and_member_labels_are_shown() {
        // Alpha is transactional (non-read-only) so the member suffix variant is exercised.
        MethodNode alpha = app("Alpha.go").className("Alpha").method("go").transactional().build();
        Set<MethodNode> cluster = Set.of(alpha, m("Beta", "run"), m("Gamma", "x"));

        String out = capture(r -> r.printCycles(List.of(cluster)));

        assertThat(out).contains(CYCLES + " 1");
        assertThat(out).contains("Cluster 1/1");
        assertThat(out).contains("(3 methods)");
        assertThat(out).contains(label("Alpha", "go"), label("Beta", "run"), label("Gamma", "x"));
        assertThat(out).contains(TX);
    }

    @Test
    void GIVEN_an_unsorted_cluster_WHEN_printing_THEN_members_are_listed_by_class_and_method() {
        Set<MethodNode> cluster = Set.of(m("Charlie", "z"), m("Alpha", "a"), m("Mike", "b"));

        String out = capture(r -> r.printCycles(List.of(cluster)));

        int alpha   = out.indexOf(label("Alpha", "a"));
        int charlie = out.indexOf(label("Charlie", "z"));
        int mike    = out.indexOf(label("Mike", "b"));
        assertThat(alpha).isLessThan(charlie);
        assertThat(charlie).isLessThan(mike);
    }

    // -----------------------------------------------------------------------
    // Longest paths
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_longest_paths_WHEN_printing_THEN_header_shows_zero_and_none_marker() {
        String out = capture(r -> r.printLongestPaths(List.of()));
        assertThat(out).contains(PATHS + " 0").contains(NONE);
    }

    @Test
    void GIVEN_a_longest_path_WHEN_printing_THEN_depth_labels_and_node_markers_are_shown() {
        MethodNode a = app("A.a").className("A").method("a").transactional().build();
        a.setReadOnly(true); // transactional + read-only suffix variant
        MethodNode ext = app("B.b").className("B").method("b")
            .project("api").calls(ExternalCallType.JDBC).build();
        var lp = new LongestPath(List.of(a, ext));

        String out = capture(r -> r.printLongestPaths(List.of(lp)));

        assertThat(out).contains(PATHS + " 1");
        assertThat(out).contains("Path 1/1");
        assertThat(out).contains("depth: 2");
        assertThat(out).contains(label("A", "a"), label("B", "b"));
        assertThat(out).contains(TX + "(readOnly)"); // transactional read-only node
        assertThat(out).contains("[JDBC]"); // external-call marker
        assertThat(out).contains("[api]");  // project label
    }

    // -----------------------------------------------------------------------
    // Lock risks
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_lock_risks_WHEN_printing_THEN_both_headers_show_zero_with_two_none_markers() {
        String out = capture(r -> r.printLockRisks(List.of(), List.of()));

        assertThat(out).contains(NESTED + " 0");
        assertThat(out).contains(CYCLIC_TX + " 0");
        assertThat(countOf(out, NONE)).isEqualTo(2);
    }

    @Test
    void GIVEN_a_nested_requires_new_risk_WHEN_printing_THEN_path_and_propagation_marker_are_shown() {
        MethodNode outer = app("Outer.run").className("Outer").method("run").transactional().build();
        MethodNode mid   = m("Mid", "step"); // non-transactional node in the path
        MethodNode inner = app("Inner.save").className("Inner").method("save").transactional().build();
        inner.setPropagation(TransactionPropagation.REQUIRES_NEW);
        var nested = new NestedTxRisk(outer, inner, List.of(outer, mid, inner));

        String out = capture(r -> r.printLockRisks(List.of(nested), List.of()));

        assertThat(out).contains(NESTED + " 1");
        assertThat(out).contains("Nested-tx 1/1");
        assertThat(out).contains(label("Outer", "run"), label("Mid", "step"), label("Inner", "save"));
        assertThat(out).contains(TX);
        assertThat(out).contains("(REQUIRES_NEW)"); // non-default propagation surfaced
    }

    @Test
    void GIVEN_a_cyclic_transactional_cluster_WHEN_printing_THEN_counts_and_member_labels_are_shown() {
        MethodNode tx    = app("A.a").className("A").method("a").transactional().build();
        MethodNode plain = m("B", "b");
        var cyclic = new CyclicTxRisk(Set.of(tx, plain), Set.of(tx));

        String out = capture(r -> r.printLockRisks(List.of(), List.of(cyclic)));

        assertThat(out).contains(CYCLIC_TX + " 1");
        assertThat(out).contains("Cyclic-tx 1/1");
        assertThat(out).contains("(2 methods, 1 @Transactional)");
        assertThat(out).contains(label("A", "a"), label("B", "b"));
        assertThat(out).contains(NESTED + " 0"); // nested section still printed, empty
    }
}
