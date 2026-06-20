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

import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.*;

/**
 * Maps a Spoon type to the named project whose source root contains it.
 *
 * <p>Use {@link #single} for the common single-project case (backward-compatible).
 * Use {@link #of} when multiple source roots are supplied via {@code --source name:path}.
 *
 * <p>Matching is done by checking which source-root path is a prefix of the
 * type's source file path. Roots are sorted longest-first so that nested roots
 * (e.g. {@code /a/b} inside {@code /a}) resolve to the most specific match.
 */
public class ProjectResolver {

    private final List<Map.Entry<Path, String>> roots; // sorted longest-first

    private ProjectResolver(Map<String, Path> nameByPath) {
        this.roots = nameByPath.entrySet().stream()
            .map(e -> Map.entry(e.getValue().toAbsolutePath().normalize(), e.getKey()))
            .sorted(Comparator.comparingInt((Map.Entry<Path, String> e) ->
                e.getKey().toString().length()).reversed())
            .toList();
    }

    // -----------------------------------------------------------------------
    // Factories
    // -----------------------------------------------------------------------

    /** Single unnamed project — nodes get an empty project name. */
    public static ProjectResolver single(String sourceRoot) {
        return new ProjectResolver(Map.of("", Path.of(sourceRoot)));
    }

    /**
     * Multiple named projects.
     *
     * @param nameByPath map of project-name → source-root path (e.g. {@code "api" → "/path/to/A"})
     */
    public static ProjectResolver of(Map<String, String> nameByPath) {
        Map<String, Path> converted = new LinkedHashMap<>();
        nameByPath.forEach((name, path) -> converted.put(name, Path.of(path)));
        return new ProjectResolver(converted);
    }

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    /**
     * Returns the project name for the given type, or {@code ""} if the type's
     * file cannot be attributed to any known source root.
     */
    public String resolve(CtType<?> type) {
        try {
            var pos = type.getPosition();
            if (!pos.isValidPosition() || pos.getFile() == null) return "";
            Path file = pos.getFile().toPath().toAbsolutePath().normalize();
            for (var entry : roots) {
                if (file.startsWith(entry.getKey())) return entry.getValue();
            }
        } catch (Exception ignored) { /* synthetic / generated types */ }
        return "";
    }

    /** True when more than one named project is registered. */
    public boolean isMultiProject() {
        return roots.size() > 1 ||
               (roots.size() == 1 && !roots.get(0).getValue().isEmpty());
    }

    /** Ordered list of source roots to add to the Spoon launcher. */
    public List<String> sourcePaths() {
        return roots.stream().map(e -> e.getKey().toString()).toList();
    }

    /** All registered project names (may include {@code ""} for the unnamed single project). */
    public List<String> projectNames() {
        return roots.stream().map(Map.Entry::getValue).toList();
    }

    /**
     * Sentinel for single-project (backward-compatible) mode.
     * {@link #resolve} always returns {@code ""}, {@link #isMultiProject} always false.
     */
    public static final ProjectResolver NONE = new ProjectResolver(Map.of());
}
