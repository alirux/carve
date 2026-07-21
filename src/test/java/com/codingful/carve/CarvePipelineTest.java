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
import com.codingful.carve.extractor.UserDefinedMarkers;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the phase-level seams of {@link Carve} directly: the usage-error exit
 * code, the {@code --classpath} branch of {@link Carve#buildSpoonModel}, the
 * Lombok-detection branch of {@link Carve#extractCallGraph}, and the markers
 * resolution rules in {@link Carve#resolveMarkers}. The happy path is exercised
 * end-to-end by {@link CarveE2ETest}.
 */
class CarvePipelineTest {

    private static final String FIXTURE = "src/test/resources/fixtures/sample-app";
    private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    /** A single-project config over {@code source}, with the given classpath/markers. */
    private static CarveConfig config(String source, String classpath, String markers) {
        return new CarveConfig(
            ProjectResolver.NONE, source, classpath, 21, UTF_8, markers,
            false, false, false, false, false, ".");
    }

    // -----------------------------------------------------------------------
    // run — usage-error path
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_source_argument_WHEN_running_THEN_it_prints_usage_and_returns_1() throws Exception {
        var err = new ByteArrayOutputStream();

        int code = Carve.run(new String[]{}, new PrintStream(err, true, UTF_8));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(UTF_8)).contains("Usage:");
    }

    // -----------------------------------------------------------------------
    // buildSpoonModel — the --classpath branch
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_classpath_WHEN_building_the_spoon_model_THEN_the_model_is_built(@TempDir Path dir)
            throws Exception {
        // A classpath flips Spoon into strict resolution (noClasspath=false), so the
        // source must be self-contained — a fixture with unresolved deps would fail.
        Path src = Files.createDirectory(dir.resolve("src"));
        Files.writeString(src.resolve("Widget.java"),
            "package demo; public class Widget { int id() { return 1; } }\n");

        CtModel model = Carve.buildSpoonModel(config(src.toString(), dir.toString(), null));

        assertThat(model.getAllTypes()).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // extractCallGraph — the Lombok-detection branch
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_lombok_annotated_source_WHEN_extracting_THEN_the_graph_flags_lombok(@TempDir Path src)
            throws Exception {
        Files.writeString(src.resolve("Widget.java"),
            "package demo;\n@lombok.Data public class Widget { private int id; }\n");
        CarveConfig c = config(src.toString(), null, null);

        CallGraph cg = Carve.extractCallGraph(Carve.buildSpoonModel(c), c);

        assertThat(cg.lombokDetected()).isTrue();
    }

    // -----------------------------------------------------------------------
    // resolveMarkers
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_explicit_markers_flag_WHEN_resolving_markers_THEN_the_file_is_loaded(@TempDir Path dir)
            throws Exception {
        Path markers = dir.resolve("markers.properties");
        Files.writeString(markers, "com.acme.Gateway=HTTP\n");

        UserDefinedMarkers resolved = Carve.resolveMarkers(markers.toString(), FIXTURE);

        assertThat(resolved.detect("com.acme.Gateway")).isEqualTo(ExternalCallType.HTTP);
    }

    @Test
    void GIVEN_no_flag_but_a_markers_file_next_to_the_source_WHEN_resolving_THEN_it_is_auto_discovered(
            @TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("analyzer-markers.properties"), "com.acme.Bus=MESSAGING\n");

        UserDefinedMarkers resolved = Carve.resolveMarkers(null, src.toString());

        assertThat(resolved.detect("com.acme.Bus")).isEqualTo(ExternalCallType.MESSAGING);
    }
}
