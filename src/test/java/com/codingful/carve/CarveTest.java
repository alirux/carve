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

import com.codingful.carve.Carve.Analyses;
import com.codingful.carve.Carve.CarveConfig;
import com.codingful.carve.Carve.UsageException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarveTest {

    // =======================================================================
    // parseArgs — argument parsing logic
    // =======================================================================

    @Test
    void GIVEN_a_positional_source_root_WHEN_parsing_args_THEN_single_project_config_with_defaults() {
        CarveConfig c = Carve.parseArgs(new String[]{ "/path/to/src" });

        assertThat(c.primarySourceRoot()).isEqualTo("/path/to/src");
        assertThat(c.resolver().isMultiProject()).isFalse();
        assertThat(c.javaLevel()).isEqualTo(21);
        assertThat(c.encoding()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(c.outputDir()).isEqualTo(".");
        assertThat(c.classpath()).isNull();
        assertThat(c.markersFile()).isNull();
        assertThat(c.writeDot()).isFalse();
        assertThat(c.printRisks()).isFalse();
        assertThat(c.printPaths()).isFalse();
        assertThat(c.printCycles()).isFalse();
        assertThat(c.printLockRisks()).isFalse();
    }

    @Test
    void GIVEN_named_source_entries_WHEN_parsing_args_THEN_multi_project_config() {
        CarveConfig c = Carve.parseArgs(new String[]{
            "--source", "api:/a", "--source", "worker:/b" });

        assertThat(c.resolver().isMultiProject()).isTrue();
        assertThat(c.resolver().projectNames()).containsExactlyInAnyOrder("api", "worker");
        // The primary source root is the first --source path.
        assertThat(c.primarySourceRoot()).isEqualTo("/a");
    }

    @Test
    void GIVEN_explicit_options_WHEN_parsing_args_THEN_they_override_defaults() {
        CarveConfig c = Carve.parseArgs(new String[]{
            "/src",
            "--java", "17",
            "--encoding", "ISO-8859-1",
            "--output", "/out",
            "--classpath", "/libs/x.jar",
            "--markers", "markers.properties",
            "--dot",
            "--print-risks", "--print-paths", "--print-cycles", "--print-lock-risks"
        });

        assertThat(c.javaLevel()).isEqualTo(17);
        assertThat(c.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(c.outputDir()).isEqualTo("/out");
        assertThat(c.classpath()).isEqualTo("/libs/x.jar");
        assertThat(c.markersFile()).isEqualTo("markers.properties");
        assertThat(c.writeDot()).isTrue();
        assertThat(c.printRisks()).isTrue();
        assertThat(c.printPaths()).isTrue();
        assertThat(c.printCycles()).isTrue();
        assertThat(c.printLockRisks()).isTrue();
    }

    @Test
    void GIVEN_no_source_at_all_WHEN_parsing_args_THEN_throws_usage_exception() {
        assertThatThrownBy(() -> Carve.parseArgs(new String[]{}))
            .isInstanceOf(UsageException.class);
    }

    @Test
    void GIVEN_only_options_without_a_source_WHEN_parsing_args_THEN_throws_usage_exception() {
        // First token starts with '-', so it is not treated as a positional source.
        assertThatThrownBy(() -> Carve.parseArgs(new String[]{ "--java", "21" }))
            .isInstanceOf(UsageException.class);
    }

    @Test
    void GIVEN_a_malformed_source_value_WHEN_parsing_args_THEN_throws_usage_exception() {
        assertThatThrownBy(() -> Carve.parseArgs(new String[]{ "--source", "noColonHere" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("name:path");
    }

    // =======================================================================
    // printSummary — console formatting
    // =======================================================================

    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private String runPrintSummary(CarveConfig config) {
        CallGraph cg = new CallGraph();
        cg.addEdge(app("a").build(), app("b").build()); // 2 vertices, 1 edge
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
