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

import com.codingful.carve.graph.CallGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the {@link Carve} pipeline: drives {@code main} over a
 * real sample project (parsed by Spoon) and asserts the produced reports.
 */
class CarveE2ETest {

    private static final String FIXTURE = "src/test/resources/fixtures/sample-app";

    private static final String JSON       = "analysis.json";
    private static final String GEXF       = "class-graph.gexf";
    private static final String CLASS_HTML = "class-graph.html";
    private static final String PKG_HTML   = "package-graph.html";
    private static final String DOT        = "call-graph.dot";

    @Test
    void GIVEN_a_sample_project_WHEN_running_main_THEN_the_default_reports_are_produced(@TempDir Path out)
            throws Exception {
        Carve.main(new String[]{ FIXTURE, "--output", out.toString() });

        assertThat(out.resolve(JSON)).isNotEmptyFile();
        assertThat(out.resolve(GEXF)).isNotEmptyFile();
        assertThat(out.resolve(CLASS_HTML)).isNotEmptyFile();
        assertThat(out.resolve(PKG_HTML)).isNotEmptyFile();
        assertThat(out.resolve(DOT)).doesNotExist(); // --dot not given
    }

    @Test
    void GIVEN_the_dot_and_print_flags_WHEN_running_main_THEN_it_completes_and_writes_the_dot(@TempDir Path out)
            throws Exception {
        Carve.main(new String[]{
            FIXTURE, "--output", out.toString(), "--dot",
            "--print-risks", "--print-paths", "--print-cycles", "--print-lock-risks"
        });

        assertThat(out.resolve(DOT)).isNotEmptyFile();
    }

    @Test
    void GIVEN_a_named_source_WHEN_running_main_THEN_reports_are_produced(@TempDir Path out)
            throws Exception {
        Carve.main(new String[]{ "--source", "app:" + FIXTURE, "--output", out.toString() });

        assertThat(out.resolve(JSON)).isNotEmptyFile();
    }

    @Test
    void GIVEN_a_source_root_WHEN_building_the_call_graph_THEN_it_is_populated() {
        CallGraph cg = Carve.buildCallGraph(FIXTURE, 21);

        assertThat(cg.vertexCount()).isGreaterThan(0);
        assertThat(cg.edgeCount()).isGreaterThan(0);
    }
}
