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

package com.codingful.carve;

import com.codingful.carve.extractor.ProjectResolver;
import com.codingful.carve.graph.CallGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

/** Covers {@link Carve#printSummary} — the end-of-run console summary. */
class CarveTest {

    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private String runPrintSummary(CarveConfig config) {
        CallGraph cg = new CallGraph();
        cg.addEdge(app("a").build(), app("b").build()); // 2 vertices, 1 edge
        return runPrintSummary(config, cg);
    }

    private String runPrintSummary(CarveConfig config, CallGraph cg) {
        Analyses empty = new Analyses(
            List.of(), List.of(), List.of(), Map.of(), List.of(), List.of());

        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Carve.printSummary(cg, empty, config, System.nanoTime());
        System.setOut(originalOut);
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static CarveConfig config(boolean writeDot) {
        return new CarveConfig(
            ProjectResolver.NONE, "/src", null, 21, StandardCharsets.UTF_8, null,
            false, false, false, false, writeDot, ".");
    }

    @Test
    void GIVEN_an_analysis_result_WHEN_printing_summary_THEN_counts_and_reports_are_shown() {
        String out = runPrintSummary(config(false));

        assertThat(out).contains("=== Analysis complete ===");
        assertThat(out).contains("Vertices : 2  (application: 2, stubs: 0)");
        assertThat(out).contains("Edges    : 1");
        assertThat(out).contains("Tx risks : 0");
        assertThat(out).contains("class-graph.html");
        assertThat(out).contains("package-graph.html");
        assertThat(out).contains("analysis.json");
        assertThat(out).doesNotContain("Lombok"); // no Lombok type in this graph
    }

    @Test
    void GIVEN_lombok_detected_WHEN_printing_summary_THEN_a_warning_note_is_shown() {
        CallGraph cg = new CallGraph();
        cg.addEdge(app("a").build(), app("b").build());
        cg.markLombokType();

        assertThat(runPrintSummary(config(false), cg))
            .contains("Lombok detected")
            .contains("docs/LOMBOK.md");
    }

    @Test
    void GIVEN_dot_disabled_WHEN_printing_summary_THEN_dot_is_not_listed() {
        assertThat(runPrintSummary(config(false))).doesNotContain("call-graph.dot");
    }

    @Test
    void GIVEN_dot_enabled_WHEN_printing_summary_THEN_dot_is_listed() {
        assertThat(runPrintSummary(config(true))).contains("call-graph.dot");
    }
}
