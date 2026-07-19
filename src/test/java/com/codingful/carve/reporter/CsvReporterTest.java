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

import com.codingful.carve.graph.CallGraph;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

class CsvReporterTest {

    private static final String HEADER =
        "source,sourceProject,target,targetProject,weight,chaWeight,implFanOut,edgeKind";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builder for an application method {@code <pkg>.<cls>#<id>} declared in {@code <pkg>.<cls>}. */
    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static List<String> rows(CallGraph cg) {
        StringWriter sw = new StringWriter();
        new CsvReporter().write(sw,
            ClassGraphModel.collapse(cg, List.of(), List.of(), List.of(), List.of()));
        return Arrays.stream(sw.toString().split("\\R")).filter(l -> !l.isEmpty()).toList();
    }

    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_direct_call_WHEN_writing_csv_THEN_the_row_carries_the_fqns_weight_and_kind() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app.web", "A", "1").project("api").build(),
                   method("app.svc", "B", "1").project("core").build());
        cg.addEdge(method("app.web", "A", "2").project("api").build(),
                   method("app.svc", "B", "1").project("core").build());

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,api,app.svc.B,core,2,0,0,direct");
    }

    @Test
    void GIVEN_an_inferred_call_WHEN_writing_csv_THEN_the_row_is_marked_cha() {
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app.web", "A", "1").project("api").build(),
                      method("app.svc", "B", "1").project("core").build());

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,api,app.svc.B,core,1,1,1,cha");
    }

    @Test
    void GIVEN_an_edge_backed_by_both_a_direct_and_an_inferred_call_WHEN_writing_csv_THEN_it_is_direct() {
        var target = method("app.svc", "B", "1").build();
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app.web", "A", "1").build(), target);
        cg.addChaEdge(method("app.web", "A", "2").build(), target);

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,,app.svc.B,,2,1,1,direct");
    }

    @Test
    void GIVEN_an_interface_with_one_implementation_WHEN_writing_csv_THEN_the_fan_out_is_one() {
        // CHA had no choice to make: the edge is inferred but exact.
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app.web", "A", "1").build(),
                      method("app.svc", "OnlyImpl", "1").build(), 1);

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,,app.svc.OnlyImpl,,1,1,1,cha");
    }

    @Test
    void GIVEN_an_interface_with_several_implementations_WHEN_writing_csv_THEN_the_fan_out_is_reported() {
        // Four candidates, so three of these edges cannot exist at runtime.
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app.web", "A", "1").build(),
                      method("app.svc", "FirstImpl", "1").build(), 4);

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,,app.svc.FirstImpl,,1,1,4,cha");
    }

    @Test
    void GIVEN_an_edge_resting_on_calls_of_differing_ambiguity_WHEN_writing_csv_THEN_the_worst_is_reported() {
        // One exactly-resolved call must not mask an ambiguous one on the same edge.
        var target = method("app.svc", "B", "1").build();
        CallGraph cg = new CallGraph();
        cg.addChaEdge(method("app.web", "A", "1").build(), target, 1);
        cg.addChaEdge(method("app.web", "A", "2").build(), target, 3);

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,,app.svc.B,,2,2,3,cha");
    }

    @Test
    void GIVEN_no_edges_WHEN_writing_csv_THEN_only_the_header_is_written() {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        assertThat(rows(cg)).containsExactly(HEADER);
    }

    @Test
    void GIVEN_a_call_to_a_library_target_WHEN_writing_csv_THEN_no_row_is_written() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "m").build(),
                   lib("ext.Lib#x").pkg("ext").className("Lib").method("x").build());

        assertThat(rows(cg)).containsExactly(HEADER);
    }

    @Test
    void GIVEN_a_project_name_containing_a_comma_WHEN_writing_csv_THEN_the_field_is_quoted() {
        // A --source name is free text, so it can carry the delimiter itself.
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app.web", "A", "1").project("api,legacy").build(),
                   method("app.svc", "B", "1").project("core").build());

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,\"api,legacy\",app.svc.B,core,1,0,0,direct");
    }

    @Test
    void GIVEN_a_project_name_containing_a_quote_WHEN_writing_csv_THEN_the_quote_is_doubled() {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app.web", "A", "1").project("the \"old\" api").build(),
                   method("app.svc", "B", "1").project("core").build());

        assertThat(rows(cg)).containsExactly(
            HEADER,
            "app.web.A,\"the \"\"old\"\" api\",app.svc.B,core,1,0,0,direct");
    }
}
