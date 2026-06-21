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

package com.codingful.carve.extractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectResolverTest {

    // -----------------------------------------------------------------------
    // Pure configuration behaviour (no Spoon needed)
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_single_unnamed_source_WHEN_configuring_THEN_not_multi_project_and_empty_name() {
        ProjectResolver r = ProjectResolver.single("/some/source");
        assertThat(r.isMultiProject()).isFalse();
        assertThat(r.projectNames()).containsExactly("");
        assertThat(r.sourcePaths()).hasSize(1);
    }

    @Test
    void GIVEN_a_single_named_project_WHEN_configuring_THEN_multi_project() {
        // A single but *named* project still counts as multi-project mode.
        ProjectResolver r = ProjectResolver.of(Map.of("api", "/a"));
        assertThat(r.isMultiProject()).isTrue();
        assertThat(r.projectNames()).containsExactly("api");
    }

    @Test
    void GIVEN_multiple_projects_WHEN_configuring_THEN_roots_sorted_longest_first() {
        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("outer", "/a");
        roots.put("inner", "/a/b/c");
        ProjectResolver r = ProjectResolver.of(roots);

        assertThat(r.isMultiProject()).isTrue();
        assertThat(r.projectNames()).containsExactlyInAnyOrder("outer", "inner");
        // The most specific (longest) root is registered first.
        assertThat(r.sourcePaths().get(0)).endsWith("/a/b/c");
    }

    @Test
    void GIVEN_the_none_sentinel_WHEN_resolving_THEN_empty_and_single_project() {
        assertThat(ProjectResolver.NONE.isMultiProject()).isFalse();
        assertThat(ProjectResolver.NONE.sourcePaths()).isEmpty();
        assertThat(ProjectResolver.NONE.projectNames()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // resolve() against real Spoon types parsed from files on disk
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_types_under_distinct_roots_WHEN_resolving_THEN_attributed_to_its_source_root(@TempDir Path dir) throws IOException {
        // Resolve symlinks up front: on macOS @TempDir is /var/... while Spoon
        // reports the canonical /private/var/... for the parsed file.
        Path tmp = dir.toRealPath();
        Path rootA = writeClass(tmp.resolve("a"), "Alpha");
        Path rootB = writeClass(tmp.resolve("b"), "Beta");

        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("A", rootA.toString());
        roots.put("B", rootB.toString());
        ProjectResolver resolver = ProjectResolver.of(roots);

        CtModel model = buildModel(rootA, rootB);

        assertThat(resolver.resolve(typeNamed(model, "Alpha"))).isEqualTo("A");
        assertThat(resolver.resolve(typeNamed(model, "Beta"))).isEqualTo("B");
    }

    @Test
    void GIVEN_nested_roots_WHEN_resolving_THEN_most_specific_root_wins(@TempDir Path dir) throws IOException {
        Path tmp = dir.toRealPath();
        Path outer = tmp.resolve("outer");
        Path inner = outer.resolve("nested");
        writeClass(outer, "OuterClass");
        Path innerFile = writeClass(inner, "InnerClass");

        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("outer", outer.toString());
        roots.put("inner", inner.toString());
        ProjectResolver resolver = ProjectResolver.of(roots);

        CtModel model = buildModel(outer); // outer contains inner

        // InnerClass lives under both roots; the longest-first ordering wins.
        assertThat(resolver.resolve(typeNamed(model, "InnerClass"))).isEqualTo("inner");
        assertThat(resolver.resolve(typeNamed(model, "OuterClass"))).isEqualTo("outer");
        assertThat(innerFile).exists();
    }

    @Test
    void GIVEN_a_type_outside_all_roots_WHEN_resolving_THEN_empty(@TempDir Path dir) throws IOException {
        Path tmp = dir.toRealPath();
        Path known = writeClass(tmp.resolve("known"), "Known");
        Path other = writeClass(tmp.resolve("other"), "Other");

        ProjectResolver resolver = ProjectResolver.of(Map.of("known", known.toString()));
        CtModel model = buildModel(other); // only the un-registered root is parsed

        assertThat(resolver.resolve(typeNamed(model, "Other"))).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers — real filesystem + real Spoon model
    // -----------------------------------------------------------------------

    /** Writes {@code public class <name> {}} into {@code dir} and returns {@code dir}. */
    private static Path writeClass(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".java"), "public class " + name + " {}");
        return dir;
    }

    private static CtModel buildModel(Path... sourceRoots) {
        Launcher launcher = new Launcher();
        for (Path root : sourceRoots) launcher.addInputResource(root.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setShouldCompile(false);
        return launcher.buildModel();
    }

    private static CtType<?> typeNamed(CtModel model, String simpleName) {
        return model.getAllTypes().stream()
            .filter(t -> t.getSimpleName().equals(simpleName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("type not found: " + simpleName));
    }
}
