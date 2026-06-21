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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed and validated command-line configuration.
 *
 * <p>{@link #parse} is the single entry point: it turns raw {@code args} into an
 * immutable config, throwing {@link UsageException} on any malformed input so the
 * caller can print usage and exit — keeping parsing free of {@code System.exit}
 * and therefore unit-testable.
 */
public record CarveConfig(
    ProjectResolver resolver,
    String primarySourceRoot,
    String classpath,
    int javaLevel,
    Charset encoding,
    String markersFile,
    boolean printRisks,
    boolean printPaths,
    boolean printCycles,
    boolean printLockRisks,
    boolean writeDot,
    String outputDir
) {

    private static final Logger log = LoggerFactory.getLogger(CarveConfig.class);

    /**
     * Parses and validates the command line.
     *
     * @throws UsageException if no source is given or any option is malformed.
     */
    public static CarveConfig parse(String[] args) {
        List<String> sourceArgs = argValues(args, "--source");
        boolean hasPositionalSource = args.length > 0 && !args[0].startsWith("-");

        if (sourceArgs.isEmpty() && !hasPositionalSource) {
            throw new UsageException("No source root specified.");
        }

        String  classpath    = argValue(args, "--classpath", null);
        int     javaLevel    = parseJavaLevel(argValue(args, "--java", "21"));
        Charset encoding     = parseEncoding(argValue(args, "--encoding", "UTF-8"));
        String  markersFile  = argValue(args, "--markers", null);
        boolean printRisks   = hasFlag(args, "--print-risks");
        boolean printPaths   = hasFlag(args, "--print-paths");
        boolean printCycles  = hasFlag(args, "--print-cycles");
        boolean printLocks   = hasFlag(args, "--print-lock-risks");
        boolean writeDot     = hasFlag(args, "--dot");
        String  outputDir    = argValue(args, "--output", ".");

        // An explicitly supplied markers file must exist: fail fast with a clean
        // usage error rather than letting a NoSuchFileException surface later as a
        // raw stack trace from deep in the extraction phase.
        if (markersFile != null && !Files.exists(Path.of(markersFile))) {
            throw new UsageException("Markers file not found: " + markersFile);
        }

        // The output dir is created later if absent, but if the path already exists
        // as a non-directory writeReports would blow up with FileAlreadyExistsException
        // — reject it up front with a clean message.
        Path outPath = Path.of(outputDir);
        if (Files.exists(outPath) && !Files.isDirectory(outPath)) {
            throw new UsageException("Output path is not a directory: " + outputDir);
        }

        // Every concrete classpath entry must exist (wildcard globs are passed
        // through to Spoon untouched).
        if (classpath != null) {
            for (String entry : classpath.split(":")) {
                if (entry.isBlank() || entry.indexOf('*') >= 0 || entry.indexOf('?') >= 0) continue;
                if (!Files.exists(Path.of(entry))) {
                    throw new UsageException("Classpath entry not found: " + entry);
                }
            }
        }

        ProjectResolver resolver;
        String primarySourceRoot;

        if (!sourceArgs.isEmpty()) {
            Map<String, String> nameByPath = new LinkedHashMap<>();
            for (String s : sourceArgs) {
                int colon = s.indexOf(':');
                if (colon <= 0) {
                    throw new UsageException("Invalid --source value '" + s
                        + "': expected format is 'name:path'");
                }
                String sourcePath = s.substring(colon + 1);
                requireSourceRoot(sourcePath);
                nameByPath.put(s.substring(0, colon), sourcePath);
            }
            resolver = ProjectResolver.of(nameByPath);
            primarySourceRoot = nameByPath.values().iterator().next();
            log.info("Projects: {}  Java level: {}  Encoding: {}  Classpath: {}  Markers: {}",
                resolver.projectNames(), javaLevel, encoding, classpath, markersFile);
        } else {
            primarySourceRoot = args[0];
            requireSourceRoot(primarySourceRoot);
            resolver = ProjectResolver.NONE;
            log.info("Source: {}  Java level: {}  Encoding: {}  Classpath: {}  Markers: {}",
                primarySourceRoot, javaLevel, encoding, classpath, markersFile);
        }

        return new CarveConfig(resolver, primarySourceRoot, classpath, javaLevel, encoding,
            markersFile, printRisks, printPaths, printCycles, printLocks, writeDot, outputDir);
    }

    // -----------------------------------------------------------------------
    // Argument helpers
    // -----------------------------------------------------------------------

    /** Returns all values for a repeatable flag (e.g. {@code --source name:path}). */
    private static List<String> argValues(String[] args, String flag) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                result.add(args[i + 1]);
                i++; // skip the value
            }
        }
        return result;
    }

    private static String argValue(String[] args, String flag, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return defaultValue;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }

    /** Parses {@code --java} into a positive compliance level, or fails with a usage error. */
    private static int parseJavaLevel(String value) {
        try {
            int level = Integer.parseInt(value.trim());
            if (level <= 0) {
                throw new UsageException("Invalid Java level (expected a positive integer): " + value);
            }
            return level;
        } catch (NumberFormatException e) {
            throw new UsageException("Invalid Java level (expected a positive integer): " + value);
        }
    }

    /** Resolves {@code --encoding} into a {@link Charset}, or fails with a usage error. */
    private static Charset parseEncoding(String value) {
        try {
            return Charset.forName(value);
        } catch (IllegalArgumentException e) { // unsupported or syntactically illegal charset name
            throw new UsageException("Unsupported encoding: " + value);
        }
    }

    /** Fails with a usage error when a declared source root does not exist on disk. */
    private static void requireSourceRoot(String path) {
        if (!Files.exists(Path.of(path))) {
            throw new UsageException("Source root not found: " + path);
        }
    }

    /**
     * Thrown by {@link #parse} on an invalid command line. The caller catches it,
     * prints the message plus usage, and exits non-zero — keeping {@code parse} a
     * pure, testable function free of {@code System.exit}.
     */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) { super(message); }
    }
}
