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
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportWriterTest {

    // The report file names ReportWriter is contracted to produce.
    private static final String JSON       = "analysis.json";
    private static final String GEXF       = "class-graph.gexf";
    private static final String CLASS_HTML = "class-graph.html";
    private static final String PKG_HTML   = "package-graph.html";
    private static final String DOT        = "call-graph.dot";

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static CallGraph graph() {
        CallGraph cg = new CallGraph();
        MethodNode a = app("app.A#m").pkg("app.web").className("A").method("m").transactional().build();
        MethodNode b = app("app.B#n").pkg("app.svc").className("B").method("n")
            .calls(ExternalCallType.HTTP).build();
        cg.addEdge(a, b);
        return cg;
    }

    private static Analyses emptyAnalyses() {
        return new Analyses(List.of(), List.of(), List.of(), Map.of(), List.of(), List.of());
    }

    private static CarveConfig config(Path outputDir, boolean writeDot) {
        return new CarveConfig(ProjectResolver.NONE, "src", null, 21, StandardCharsets.UTF_8,
            null, false, false, false, false, writeDot, outputDir.toString());
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_model_WHEN_writing_reports_THEN_the_default_files_are_created(@TempDir Path out)
            throws IOException {
        ReportWriter.write(graph(), emptyAnalyses(), config(out, false));

        for (String name : List.of(JSON, GEXF, CLASS_HTML, PKG_HTML)) {
            assertThat(out.resolve(name)).isNotEmptyFile();
        }
    }

    @Test
    void GIVEN_dot_disabled_WHEN_writing_reports_THEN_no_dot_file_is_written(@TempDir Path out)
            throws IOException {
        ReportWriter.write(graph(), emptyAnalyses(), config(out, false));

        assertThat(out.resolve(DOT)).doesNotExist();
    }

    @Test
    void GIVEN_dot_enabled_WHEN_writing_reports_THEN_the_method_dot_is_written(@TempDir Path out)
            throws IOException {
        ReportWriter.write(graph(), emptyAnalyses(), config(out, true));

        assertThat(out.resolve(DOT)).isNotEmptyFile();
    }

    @Test
    void GIVEN_a_missing_output_dir_WHEN_writing_reports_THEN_it_is_created(@TempDir Path tmp)
            throws IOException {
        Path out = tmp.resolve("nested/does/not/exist");

        ReportWriter.write(graph(), emptyAnalyses(), config(out, false));

        assertThat(out).isDirectory();
        assertThat(out.resolve(JSON)).isNotEmptyFile();
    }

    @Test
    void GIVEN_reports_WHEN_writing_THEN_json_is_valid_and_gexf_is_well_formed(@TempDir Path out)
            throws Exception {
        ReportWriter.write(graph(), emptyAnalyses(), config(out, false));

        var json = JsonMapper.builder().build().readTree(Files.readString(out.resolve(JSON)));
        assertThat(json.has("summary")).isTrue();

        // Parsing validates the GEXF is well-formed XML.
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new InputSource(new StringReader(Files.readString(out.resolve(GEXF)))));
    }

    @Test
    void GIVEN_an_unwritable_report_path_WHEN_writing_THEN_an_io_exception_surfaces(@TempDir Path out)
            throws IOException {
        // A directory where analysis.json should go makes that report fail to write;
        // the async failure must surface as a checked IOException, not an unchecked one.
        Files.createDirectory(out.resolve(JSON));

        assertThatThrownBy(() -> ReportWriter.write(graph(), emptyAnalyses(), config(out, false)))
            .isInstanceOf(IOException.class);
    }
}
