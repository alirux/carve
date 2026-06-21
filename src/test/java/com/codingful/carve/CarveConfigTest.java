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

import com.codingful.carve.CarveConfig.UsageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarveConfigTest {

    // -----------------------------------------------------------------------
    // Happy paths
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_positional_source_root_WHEN_parsing_args_THEN_single_project_config_with_defaults(
            @TempDir Path src) {
        CarveConfig c = CarveConfig.parse(new String[]{ src.toString() });

        assertThat(c.primarySourceRoot()).isEqualTo(src.toString());
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
    void GIVEN_named_source_entries_WHEN_parsing_args_THEN_multi_project_config(@TempDir Path tmp)
            throws IOException {
        Path api    = Files.createDirectories(tmp.resolve("api"));
        Path worker = Files.createDirectories(tmp.resolve("worker"));

        CarveConfig c = CarveConfig.parse(new String[]{
            "--source", "api:" + api, "--source", "worker:" + worker });

        assertThat(c.resolver().isMultiProject()).isTrue();
        assertThat(c.resolver().projectNames()).containsExactlyInAnyOrder("api", "worker");
        // The primary source root is the first --source path.
        assertThat(c.primarySourceRoot()).isEqualTo(api.toString());
    }

    @Test
    void GIVEN_explicit_options_WHEN_parsing_args_THEN_they_override_defaults(@TempDir Path tmp)
            throws IOException {
        Path src     = Files.createDirectories(tmp.resolve("src"));
        Path jar     = Files.writeString(tmp.resolve("x.jar"), "");
        Path markers = Files.writeString(tmp.resolve("markers.properties"), "");
        Path out     = tmp.resolve("out"); // missing: accepted, created later by writeReports

        CarveConfig c = CarveConfig.parse(new String[]{
            src.toString(),
            "--java", "17",
            "--encoding", "ISO-8859-1",
            "--output", out.toString(),
            "--classpath", jar.toString(),
            "--markers", markers.toString(),
            "--dot",
            "--print-risks", "--print-paths", "--print-cycles", "--print-lock-risks"
        });

        assertThat(c.javaLevel()).isEqualTo(17);
        assertThat(c.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(c.outputDir()).isEqualTo(out.toString());
        assertThat(c.classpath()).isEqualTo(jar.toString());
        assertThat(c.markersFile()).isEqualTo(markers.toString());
        assertThat(c.writeDot()).isTrue();
        assertThat(c.printRisks()).isTrue();
        assertThat(c.printPaths()).isTrue();
        assertThat(c.printCycles()).isTrue();
        assertThat(c.printLockRisks()).isTrue();
    }

    @Test
    void GIVEN_a_non_existent_output_dir_WHEN_parsing_args_THEN_it_is_accepted(@TempDir Path tmp)
            throws IOException {
        // A missing output dir is created later by writeReports, so it must not be rejected here.
        Path src     = Files.createDirectories(tmp.resolve("src"));
        Path missing = tmp.resolve("does/not/exist/yet");

        CarveConfig c = CarveConfig.parse(new String[]{ src.toString(), "--output", missing.toString() });

        assertThat(c.outputDir()).isEqualTo(missing.toString());
    }

    @Test
    void GIVEN_a_classpath_with_a_wildcard_WHEN_parsing_args_THEN_it_is_accepted(@TempDir Path tmp)
            throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        // Globs are passed through to Spoon untouched, so the literal path need not exist.
        String glob = tmp.resolve("libs").resolve("*.jar").toString();

        CarveConfig c = CarveConfig.parse(new String[]{ src.toString(), "--classpath", glob });

        assertThat(c.classpath()).isEqualTo(glob);
    }

    // -----------------------------------------------------------------------
    // Usage errors
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_source_at_all_WHEN_parsing_args_THEN_throws_usage_exception() {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{}))
            .isInstanceOf(UsageException.class);
    }

    @Test
    void GIVEN_only_options_without_a_source_WHEN_parsing_args_THEN_throws_usage_exception() {
        // First token starts with '-', so it is not treated as a positional source.
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ "--java", "21" }))
            .isInstanceOf(UsageException.class);
    }

    @Test
    void GIVEN_a_malformed_source_value_WHEN_parsing_args_THEN_throws_usage_exception() {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ "--source", "noColonHere" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("name:path");
    }

    @Test
    void GIVEN_a_non_existent_source_root_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path tmp) {
        Path missing = tmp.resolve("missing-src");
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ missing.toString() }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Source root not found");
    }

    @Test
    void GIVEN_a_named_source_with_a_missing_path_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path tmp) {
        Path missing = tmp.resolve("missing-api");
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ "--source", "api:" + missing }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Source root not found");
    }

    @Test
    void GIVEN_a_missing_markers_file_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path src) {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{
                src.toString(), "--markers", "/no/such/markers.properties" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Markers file not found");
    }

    @Test
    void GIVEN_an_output_path_that_is_a_file_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path tmp)
            throws IOException {
        Path src  = Files.createDirectories(tmp.resolve("src"));
        Path file = Files.writeString(tmp.resolve("not-a-dir"), "");

        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ src.toString(), "--output", file.toString() }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("not a directory");
    }

    @Test
    void GIVEN_a_non_numeric_java_level_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path src) {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ src.toString(), "--java", "notANumber" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Java level");
    }

    @Test
    void GIVEN_a_non_positive_java_level_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path src) {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ src.toString(), "--java", "0" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Java level");
    }

    @Test
    void GIVEN_an_unknown_encoding_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path src) {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ src.toString(), "--encoding", "NO-SUCH-ENC" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("encoding");
    }

    @Test
    void GIVEN_a_non_existent_classpath_entry_WHEN_parsing_args_THEN_throws_usage_exception(@TempDir Path src) {
        assertThatThrownBy(() -> CarveConfig.parse(new String[]{ src.toString(), "--classpath", "/no/such.jar" }))
            .isInstanceOf(UsageException.class)
            .hasMessageContaining("Classpath entry not found");
    }
}
