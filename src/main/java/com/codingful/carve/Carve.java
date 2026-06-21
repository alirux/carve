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

import com.codingful.carve.analyzer.CouplingAnalyzer;
import com.codingful.carve.analyzer.LockRiskAnalyzer;
import com.codingful.carve.analyzer.PathAnalyzer;
import com.codingful.carve.analyzer.TransactionAnalyzer;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.extractor.CallGraphExtractor;
import com.codingful.carve.extractor.ProjectResolver;
import com.codingful.carve.extractor.UserDefinedMarkers;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.reporter.ClassGraphModel;
import com.codingful.carve.reporter.ConsoleReporter;
import com.codingful.carve.reporter.DotReporter;
import com.codingful.carve.reporter.GexfReporter;
import com.codingful.carve.reporter.HtmlReporter;
import com.codingful.carve.reporter.JsonReporter;
import com.codingful.carve.reporter.PackageGraphModel;
import com.codingful.carve.reporter.PackageHtmlReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * CLI entry point.
 *
 * <pre>
 * Usage:
 *   java -jar carve.jar &lt;source-root&gt; [--classpath &lt;cp&gt;] [--java &lt;version&gt;]
 *
 * Examples:
 *   java -jar carve.jar /path/to/src/main/java
 *   java -jar carve.jar /path/to/src/main/java --classpath /path/to/lib/*.jar --java 21
 * </pre>
 *
 * Outputs files in the output directory (default: current directory):
 * <ul>
 *   <li>{@code class-graph.html}   — Interactive class-level graph (open in browser)</li>
 *   <li>{@code package-graph.html} — Interactive package-level graph (open in browser)</li>
 *   <li>{@code class-graph.gexf}   — Class graph for Gephi</li>
 *   <li>{@code analysis.json}      — Full analysis results (risks, paths, coupling)</li>
 *   <li>{@code call-graph.dot}     — Method-level Graphviz DOT (opt-in via {@code --dot})</li>
 * </ul>
 */
public class Carve {

    private static final Logger log = LoggerFactory.getLogger(Carve.class);

    private static final String USAGE = """
        Usage: carve <source-root> [options]
           or: carve --source <name>:<path> [--source <name>:<path> ...] [options]

        Options:
          --classpath <path>         Colon-separated JARs for type resolution
          --java <version>           Java compliance level (default: 21)
          --encoding <name>          Source encoding (default: UTF-8)
          --markers <file>           Custom external-call markers (.properties)
          --print-risks             Print transaction risk call stacks to console
          --print-paths             Print the 10 longest call chains to console
          --print-cycles            Print cyclic method clusters to console
          --print-lock-risks        Print DB lock/deadlock risk patterns to console
          --dot                     Also write method-level call-graph.dot (Graphviz)
          --output <dir>            Output directory (default: current dir)
        """;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        CarveConfig config;
        try {
            config = parseArgs(args);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return; // unreachable; satisfies definite-assignment of config below
        }
        long t0 = System.nanoTime();

        CtModel   model  = buildSpoonModel(config);
        CallGraph cg     = extractCallGraph(model, config);
        Analyses  result = runAnalyses(cg, config);
        writeReports(cg, result, config);
        printSummary(cg, result, config, t0);
    }

    // -----------------------------------------------------------------------
    // Pipeline phases
    // -----------------------------------------------------------------------

    static CarveConfig parseArgs(String[] args) {
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

    static CtModel buildSpoonModel(CarveConfig c) {
        Launcher launcher = new Launcher();
        if (c.resolver().isMultiProject()) {
            c.resolver().sourcePaths().forEach(launcher::addInputResource);
        } else {
            launcher.addInputResource(c.primarySourceRoot());
        }
        if (c.classpath() != null) {
            launcher.getEnvironment().setSourceClasspath(c.classpath().split(":"));
        }
        launcher.getEnvironment().setComplianceLevel(c.javaLevel());
        launcher.getEnvironment().setEncoding(c.encoding());
        // Do not compile — analysis only
        launcher.getEnvironment().setShouldCompile(false);
        // Log type-resolution warnings at WARN level (suppress in production via logback.xml)
        launcher.getEnvironment().setNoClasspath(c.classpath() == null);

        log.info("Building Spoon model…");
        long tSpoon = System.nanoTime();
        CtModel model = launcher.buildModel();
        log.info("Model built: {} types  [{}]", model.getAllTypes().size(), elapsed(tSpoon));
        return model;
    }

    static CallGraph extractCallGraph(CtModel model, CarveConfig c) throws IOException {
        UserDefinedMarkers customMarkers = resolveMarkers(c.markersFile(), c.primarySourceRoot());
        CallGraph callGraph = new CallGraph();
        long tExtract = System.nanoTime();
        CallGraphExtractor extractor =
            new CallGraphExtractor(callGraph, customMarkers, c.resolver());
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();
        log.info("Call graph: {}  [{}]", callGraph, elapsed(tExtract));
        return callGraph;
    }

    static Analyses runAnalyses(CallGraph cg, CarveConfig c) {
        long tRisks = System.nanoTime();
        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();
        log.info("Transaction risks: {}  [{}]", risks.size(), elapsed(tRisks));

        long tPaths = System.nanoTime();
        List<PathAnalyzer.LongestPath> longestPaths =
            new PathAnalyzer(cg).findLongestPaths(10);
        log.info("Longest paths: top {}  [{}]", longestPaths.size(), elapsed(tPaths));

        long tCoupling = System.nanoTime();
        CouplingAnalyzer couplingAnalyzer = new CouplingAnalyzer(cg);
        List<Set<MethodNode>> cyclicClusters = couplingAnalyzer.findCyclicClusters();
        Map<String, CouplingAnalyzer.PackageCoupling> coupling =
            couplingAnalyzer.analysePackageCoupling();
        log.info("Coupling analysis: {} cyclic clusters  [{}]",
            cyclicClusters.size(), elapsed(tCoupling));

        ConsoleReporter console = new ConsoleReporter(System.out);
        if (c.printRisks())   console.print(risks);
        if (c.printPaths())   console.printLongestPaths(longestPaths);
        if (c.printCycles())  console.printCycles(cyclicClusters);

        long tLock = System.nanoTime();
        LockRiskAnalyzer lockAnalyzer = new LockRiskAnalyzer(cg);
        List<LockRiskAnalyzer.NestedTxRisk> nestedTxRisks =
            lockAnalyzer.findNestedRequiresNewRisks();
        List<LockRiskAnalyzer.CyclicTxRisk> cyclicTxRisks =
            lockAnalyzer.findCyclicTransactionRisks(cyclicClusters);
        log.info("Lock risks: {} nested-tx, {} cyclic-tx  [{}]",
            nestedTxRisks.size(), cyclicTxRisks.size(), elapsed(tLock));

        if (c.printLockRisks()) console.printLockRisks(nestedTxRisks, cyclicTxRisks);

        return new Analyses(risks, longestPaths, cyclicClusters, coupling,
            nestedTxRisks, cyclicTxRisks);
    }

    static void writeReports(CallGraph cg, Analyses r, CarveConfig c)
            throws IOException {
        Path outPath = Path.of(c.outputDir());
        Files.createDirectories(outPath);

        Path jsonFile    = outPath.resolve("analysis.json");
        Path dotFile     = outPath.resolve("call-graph.dot");
        Path gexfFile    = outPath.resolve("class-graph.gexf");
        Path htmlFile    = outPath.resolve("class-graph.html");
        Path pkgHtmlFile = outPath.resolve("package-graph.html");

        // Class-level collapse is shared by GEXF and HTML; compute once on the main thread.
        long tCollapse = System.nanoTime();
        ClassGraphModel classModel = ClassGraphModel.collapse(
            cg, r.risks(), r.cyclicClusters(), r.nestedTxRisks(), r.cyclicTxRisks());
        log.info("Class model collapsed: {} classes, {} edges  [{}]",
            classModel.nodes().size(), classModel.edges().size(), elapsed(tCollapse));

        var hotspotsByPkg = CouplingAnalyzer.classifyHotspots(r.coupling().values()).byPackage();
        PackageGraphModel pkgModel = PackageGraphModel.collapse(classModel, hotspotsByPkg);
        log.info("Package model collapsed: {} packages, {} edges ({} hotspots)",
            pkgModel.nodes().size(), pkgModel.edges().size(), hotspotsByPkg.size());

        List<CompletableFuture<Void>> reportTasks = new ArrayList<>();
        long tReports = System.nanoTime();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {

            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(jsonFile)) {
                    new JsonReporter(cg).write(w, r.risks(), r.longestPaths(),
                        r.cyclicClusters(), r.coupling(), r.nestedTxRisks(), r.cyclicTxRisks());
                }
                log.info("JSON written: {}  [{}]", jsonFile, elapsed(t));
            }), exec));

            // Method-level DOT is opt-in: on a large monolith its SVG is an unreadable
            // hairball, so the interactive class-level exports below are the default.
            if (c.writeDot()) {
                reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                    long t = System.nanoTime();
                    try (var w = Files.newBufferedWriter(dotFile)) {
                        new DotReporter(cg, r.cyclicClusters())
                            .writeWithRisks(w, true, r.risks());
                    }
                    log.info("DOT written: {}  [{}]", dotFile, elapsed(t));
                }), exec));
            }

            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(gexfFile)) {
                    new GexfReporter().write(w, classModel);
                }
                log.info("GEXF written: {} ({} classes, {} edges)  [{}]",
                    gexfFile, classModel.nodes().size(), classModel.edges().size(), elapsed(t));
            }), exec));

            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(htmlFile)) {
                    new HtmlReporter().write(w, classModel);
                }
                log.info("HTML written: {}  [{}]", htmlFile, elapsed(t));
            }), exec));

            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(pkgHtmlFile)) {
                    new PackageHtmlReporter().write(w, pkgModel);
                }
                log.info("Package HTML written: {}  [{}]", pkgHtmlFile, elapsed(t));
            }), exec));

            try {
                CompletableFuture.allOf(reportTasks.toArray(CompletableFuture[]::new)).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof UncheckedIOException uio) throw uio.getCause();
                throw new IOException(cause);
            }
        }
        log.info("Reports written (wall clock)  [{}]", elapsed(tReports));
    }

    static void printSummary(CallGraph cg, Analyses r, CarveConfig c, long t0) {
        Path outPath     = Path.of(c.outputDir());
        Path htmlFile    = outPath.resolve("class-graph.html");
        Path pkgHtmlFile = outPath.resolve("package-graph.html");
        Path gexfFile    = outPath.resolve("class-graph.gexf");
        Path jsonFile    = outPath.resolve("analysis.json");
        Path dotFile     = outPath.resolve("call-graph.dot");

        String reports = String.join("\n           ",
            htmlFile.toAbsolutePath() + " (open in browser)",
            pkgHtmlFile.toAbsolutePath() + " (open in browser)",
            gexfFile.toAbsolutePath() + " (open in Gephi)",
            jsonFile.toAbsolutePath().toString())
            + (c.writeDot() ? "\n           " + dotFile.toAbsolutePath() : "");

        int appCount = cg.applicationNodes().size();
        System.out.printf("""
            %n=== Analysis complete ===%n
            Vertices : %d  (application: %d, stubs: %d)
            Edges    : %d
            Tx risks : %d
            Total    : %s%n
            Reports  : %s%n
            """,
            cg.vertexCount(), appCount, cg.vertexCount() - appCount,
            cg.edgeCount(),
            r.risks().size(),
            elapsed(t0),
            reports
        );
    }

    // -----------------------------------------------------------------------
    // Programmatic API — for use in tests or embedding in other tools
    // -----------------------------------------------------------------------

    /**
     * Parses the given source root (no extra classpath) and returns a
     * pre-populated call graph ready for analysis.
     */
    public static CallGraph buildCallGraph(String sourceRoot, int javaLevel) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceRoot);
        launcher.getEnvironment().setComplianceLevel(javaLevel);
        launcher.getEnvironment().setShouldCompile(false);
        launcher.getEnvironment().setNoClasspath(true);

        CtModel model = launcher.buildModel();

        CallGraph callGraph = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(callGraph);
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();

        return callGraph;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the markers file to load.
     *
     * <ol>
     *   <li>If {@code --markers} was supplied explicitly, use that path.</li>
     *   <li>Otherwise look for {@code analyzer-markers.properties} next to the
     *       source root (useful when the file lives alongside the project).</li>
     *   <li>Then look in the current working directory.</li>
     *   <li>If nothing is found, return {@link UserDefinedMarkers#EMPTY}.</li>
     * </ol>
     */
    private static UserDefinedMarkers resolveMarkers(String markersFlag, String sourceRoot)
            throws IOException {

        if (markersFlag != null) {
            return UserDefinedMarkers.fromFile(Path.of(markersFlag));
        }

        // Auto-discovery: look next to the source root first, then cwd.
        String conventional = "analyzer-markers.properties";
        Path[] candidates = {
            Path.of(sourceRoot).toAbsolutePath().getParent().resolve(conventional),
            Path.of(".").toAbsolutePath().resolve(conventional)
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                log.info("Auto-discovered markers file: {}", candidate);
                return UserDefinedMarkers.fromFile(candidate);
            }
        }

        return UserDefinedMarkers.EMPTY;
    }

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

    /** Returns elapsed milliseconds since {@code startNano} as a loggable string. */
    private static String elapsed(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000 + " ms";
    }

    /**
     * Thrown by {@link #parseArgs} on an invalid command line. {@link #main}
     * catches it, prints the message plus usage, and exits non-zero — keeping
     * {@code parseArgs} a pure, testable function free of {@code System.exit}.
     */
    static final class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    private static Runnable ioTask(IoRunnable r) {
        return () -> {
            try {
                r.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Internal records
    // -----------------------------------------------------------------------

    record CarveConfig(
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
    ) {}

    record Analyses(
        List<TransactionRisk> risks,
        List<PathAnalyzer.LongestPath> longestPaths,
        List<Set<MethodNode>> cyclicClusters,
        Map<String, CouplingAnalyzer.PackageCoupling> coupling,
        List<LockRiskAnalyzer.NestedTxRisk> nestedTxRisks,
        List<LockRiskAnalyzer.CyclicTxRisk> cyclicTxRisks
    ) {}
}
