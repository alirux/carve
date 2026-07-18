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
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.extractor.CallGraphExtractor;
import com.codingful.carve.extractor.ProjectResolver;
import com.codingful.carve.extractor.UserDefinedMarkers;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.reporter.ClassGraphModel.Edge;
import com.codingful.carve.reporter.ClassGraphModel.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

class ClassGraphModelTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builder for an application method {@code <pkg>.<cls>#<id>} declared in {@code <pkg>.<cls>}. */
    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static ClassGraphModel collapse(CallGraph cg) {
        return ClassGraphModel.collapse(cg, List.of(), List.of(), List.of(), List.of());
    }

    private static Node node(ClassGraphModel model, String fqn) {
        return model.nodes().stream()
            .filter(n -> n.id().equals(fqn))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no node: " + fqn));
    }

    // -----------------------------------------------------------------------
    // Method → class collapse
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_methods_of_the_same_class_WHEN_collapsing_THEN_one_node_aggregates_them() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "a").build());
        cg.addVertex(method("app", "Svc", "b").build());

        ClassGraphModel model = collapse(cg);

        assertThat(model.nodes()).hasSize(1);
        Node n = node(model, "app.Svc");
        assertThat(n.label()).isEqualTo("Svc");
        assertThat(n.project()).isEmpty();
        assertThat(n.methods()).isEqualTo(2);
        assertThat(n.transactional()).isFalse();
        assertThat(n.external()).isFalse();
    }

    @Test
    void GIVEN_a_library_method_WHEN_collapsing_THEN_it_is_excluded_from_nodes() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());
        cg.addVertex(lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build());

        assertThat(collapse(cg).nodes()).extracting(Node::id).containsExactly("app.A");
    }

    // -----------------------------------------------------------------------
    // Attribute aggregation (OR semantics)
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_methods_with_mixed_attributes_WHEN_collapsing_THEN_class_flags_are_ored() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "a").transactional().build());
        cg.addVertex(method("app", "Svc", "b").calls(ExternalCallType.HTTP).build());

        Node n = node(collapse(cg), "app.Svc");

        assertThat(n.transactional()).isTrue();
        assertThat(n.external()).isTrue();
        assertThat(n.externalCalls()).containsExactly("HTTP");
    }

    @Test
    void GIVEN_several_external_call_types_WHEN_collapsing_THEN_they_are_sorted_and_deduped() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "a").calls(ExternalCallType.JDBC, ExternalCallType.HTTP).build());
        cg.addVertex(method("app", "Svc", "b").calls(ExternalCallType.HTTP).build()); // duplicate HTTP

        assertThat(node(collapse(cg), "app.Svc").externalCalls()).containsExactly("HTTP", "JDBC");
    }

    @Test
    void GIVEN_methods_on_risk_and_cyclic_and_lock_paths_WHEN_collapsing_THEN_risk_flags_are_set() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode b = method("app", "B", "m").build();
        CallGraph cg = new CallGraph();
        cg.addVertex(a);
        cg.addVertex(b);

        var risk    = new TransactionRisk(a, a, Set.of(ExternalCallType.HTTP), List.of(a));
        var cluster = Set.of(a, b);
        var nested  = new NestedTxRisk(a, a, List.of(a));            // lock risk via nested path
        var cyclic  = new CyclicTxRisk(Set.of(b), Set.of(b));        // lock risk via cyclic-tx nodes

        ClassGraphModel model = ClassGraphModel.collapse(
            cg, List.of(risk), List.of(cluster), List.of(nested), List.of(cyclic));

        Node na = node(model, "app.A");
        assertThat(na.inRisk()).isTrue();
        assertThat(na.cyclic()).isTrue();
        assertThat(na.inLockRisk()).isTrue();

        Node nb = node(model, "app.B");
        assertThat(nb.inRisk()).isFalse();
        assertThat(nb.cyclic()).isTrue();
        assertThat(nb.inLockRisk()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Edges
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_cross_class_calls_WHEN_collapsing_THEN_a_weighted_edge_is_produced() {
        MethodNode a1 = method("app", "A", "1").build();
        MethodNode a2 = method("app", "A", "2").build();
        MethodNode b1 = method("app", "B", "1").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a1, b1);
        cg.addEdge(a2, b1); // two A→B method calls collapse to weight 2

        List<Edge> edges = collapse(cg).edges();

        assertThat(edges).hasSize(1);
        Edge e = edges.get(0);
        assertThat(e.source()).isEqualTo("app.A");
        assertThat(e.target()).isEqualTo("app.B");
        assertThat(e.weight()).isEqualTo(2);
    }

    @Test
    void GIVEN_only_cha_calls_between_two_classes_WHEN_collapsing_THEN_the_edge_is_marked_cha() {
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app", "A", "1").build(), method("app", "B", "1").build());

        Edge e = collapse(cg).edges().get(0);

        assertThat(e.kind()).isEqualTo("cha");
        assertThat(e.weight()).isEqualTo(1);
        assertThat(e.chaWeight()).isEqualTo(1);
    }

    @Test
    void GIVEN_a_class_pair_joined_by_both_a_direct_and_a_cha_call_WHEN_collapsing_THEN_the_edge_is_direct() {
        // One real call site is enough to make the coupling real, whatever CHA
        // adds on top of it.
        MethodNode b1 = method("app", "B", "1").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "1").build(), b1);
        cg.addChaEdge(method("app", "A", "2").build(), b1);

        Edge e = collapse(cg).edges().get(0);

        assertThat(e.kind()).isEqualTo("direct");
        assertThat(e.weight()).isEqualTo(2);
        assertThat(e.chaWeight()).isEqualTo(1);
    }

    @Test
    void GIVEN_intra_class_calls_WHEN_collapsing_THEN_no_edge_is_produced() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "1").build(), method("app", "A", "2").build());

        assertThat(collapse(cg).edges()).isEmpty();
    }

    @Test
    void GIVEN_a_call_to_a_library_target_WHEN_collapsing_THEN_no_edge_is_produced() {
        MethodNode a = method("app", "A", "m").build();
        MethodNode libNode = lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(a, libNode);

        assertThat(collapse(cg).edges()).isEmpty();
    }

    @Test
    void GIVEN_a_call_from_a_library_source_WHEN_collapsing_THEN_no_edge_is_produced() {
        MethodNode libNode = lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build();
        MethodNode a = method("app", "A", "m").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(libNode, a);

        assertThat(collapse(cg).edges()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Multi-project
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_single_project_WHEN_collapsing_THEN_multi_project_is_false() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        assertThat(collapse(cg).multiProject()).isFalse();
    }

    @Test
    void GIVEN_methods_from_two_projects_WHEN_collapsing_THEN_multi_project_is_true_and_nodes_carry_project() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("api", "A", "m").project("api").build());
        cg.addVertex(method("core", "B", "m").project("core").build());

        ClassGraphModel model = collapse(cg);

        assertThat(model.multiProject()).isTrue();
        assertThat(node(model, "api.A").project()).isEqualTo("api");
        assertThat(node(model, "core.B").project()).isEqualTo("core");
    }

    // -----------------------------------------------------------------------
    // Type-level attribution — real extractor + ProjectResolver + collapse
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_method_less_lombok_dto_that_nobody_calls_WHEN_collapsing_THEN_it_appears_attributed_to_its_project(@TempDir Path dir) throws IOException {
        // A pure @Data DTO under "core" has no methods (Lombok is not run) and is
        // referenced by nobody. Before type-level attribution it would be absent
        // from the class graph entirely; now it appears as an isolated node,
        // attributed to "core" from its own source file — no call-site guessing,
        // no classpath, no delombok.
        Path tmp = dir.toRealPath();
        Path core = writePackagedClass(tmp.resolve("core"), "com/acme/core", "Money",
            """
            package com.acme.core;
            import lombok.Data;
            @Data
            public class Money { private java.math.BigDecimal amount; }
            """);
        Path api = writePackagedClass(tmp.resolve("api"), "com/acme/api", "PriceController",
            """
            package com.acme.api;
            public class PriceController { public void show() {} }
            """);

        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("api", api.toString());
        roots.put("core", core.toString());
        ProjectResolver resolver = ProjectResolver.of(roots);

        Launcher launcher = new Launcher();
        launcher.addInputResource(api.toString());
        launcher.addInputResource(core.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor =
            new CallGraphExtractor(cg, UserDefinedMarkers.EMPTY, resolver);
        model.getAllTypes().forEach(extractor::scan);

        ClassGraphModel classModel = collapse(cg);

        assertThat(classModel.multiProject()).isTrue();

        Node money = node(classModel, "com.acme.core.Money");
        assertThat(money.project()).isEqualTo("core");
        assertThat(money.label()).isEqualTo("Money");
        assertThat(money.methods()).isZero();          // isolated: no method nodes
        assertThat(classModel.edges()).noneMatch(e ->
            e.source().equals("com.acme.core.Money")
            || e.target().equals("com.acme.core.Money"));

        // The controller under a different root is attributed to "api".
        assertThat(node(classModel, "com.acme.api.PriceController").project()).isEqualTo("api");
    }

    // -----------------------------------------------------------------------
    // Helpers for the real-extractor test
    // -----------------------------------------------------------------------

    private static Path writePackagedClass(Path root, String packagePath, String name, String source)
            throws IOException {
        Path pkgDir = root.resolve(packagePath);
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve(name + ".java"), source);
        return root;
    }
}
