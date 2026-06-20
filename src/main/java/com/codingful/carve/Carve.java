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
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Set;
import com.codingful.carve.model.MethodNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Outputs two files in the current directory:
 * <ul>
 *   <li>{@code call-graph.dot}  — Graphviz DOT (compile with {@code dot -Tsvg})</li>
 *   <li>{@code analysis.json}   — Summary, transaction risks, package coupling</li>
 * </ul>
 */
public class Carve {

    private static final Logger log = LoggerFactory.getLogger(Carve.class);

    public static void main(String[] args) throws IOException {
        // Parse --source flags (repeatable: --source name:path)
        List<String> sourceArgs = argValues(args, "--source");
        boolean hasPositionalSource = args.length > 0 && !args[0].startsWith("-");

        if (sourceArgs.isEmpty() && !hasPositionalSource) {
            System.err.println("""
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
                """);
            System.exit(1);
        }

        String classpath   = argValue(args, "--classpath", null);
        int    javaLevel   = Integer.parseInt(argValue(args, "--java", "21"));
        Charset encoding   = Charset.forName(argValue(args, "--encoding", "UTF-8"));
        String markersFile = argValue(args, "--markers", null);
        boolean printRisks     = hasFlag(args, "--print-risks");
        boolean printPaths     = hasFlag(args, "--print-paths");
        boolean printCycles    = hasFlag(args, "--print-cycles");
        boolean printLockRisks = hasFlag(args, "--print-lock-risks");
        boolean writeDot       = hasFlag(args, "--dot");
        String outputDir   = argValue(args, "--output", ".");

        // Build project resolver
        ProjectResolver resolver;
        String primarySourceRoot;

        if (!sourceArgs.isEmpty()) {
            Map<String, String> nameByPath = new LinkedHashMap<>();
            for (String s : sourceArgs) {
                int colon = s.indexOf(':');
                if (colon <= 0) {
                    System.err.println("Invalid --source value '" + s
                        + "': expected format is 'name:path'");
                    System.exit(1);
                }
                nameByPath.put(s.substring(0, colon), s.substring(colon + 1));
            }
            resolver = ProjectResolver.of(nameByPath);
            primarySourceRoot = nameByPath.values().iterator().next();
            log.info("Projects: {}  Java level: {}  Encoding: {}  Classpath: {}  Markers: {}",
                resolver.projectNames(), javaLevel, encoding, classpath, markersFile);
        } else {
            primarySourceRoot = args[0];
            resolver = ProjectResolver.NONE;
            log.info("Source: {}  Java level: {}  Encoding: {}  Classpath: {}  Markers: {}",
                primarySourceRoot, javaLevel, encoding, classpath, markersFile);
        }

        long t0 = System.nanoTime();

        // ------------------------------------------------------------------
        // 1. Parse source with Spoon
        // ------------------------------------------------------------------
        Launcher launcher = new Launcher();
        if (resolver.isMultiProject()) {
            resolver.sourcePaths().forEach(launcher::addInputResource);
        } else {
            launcher.addInputResource(primarySourceRoot);
        }
        if (classpath != null) {
            launcher.getEnvironment().setSourceClasspath(classpath.split(":"));
        }
        launcher.getEnvironment().setComplianceLevel(javaLevel);
        launcher.getEnvironment().setEncoding(encoding);
        // Do not compile — analysis only
        launcher.getEnvironment().setShouldCompile(false);
        // Log type-resolution warnings at WARN level (suppress in production via logback.xml)
        launcher.getEnvironment().setNoClasspath(classpath == null);

        log.info("Building Spoon model…");
        long tSpoon = System.nanoTime();
        CtModel model = launcher.buildModel();
        log.info("Model built: {} types  [{}]", model.getAllTypes().size(), elapsed(tSpoon));

        // ------------------------------------------------------------------
        // 2. Extract call graph
        // ------------------------------------------------------------------
        UserDefinedMarkers customMarkers = resolveMarkers(markersFile, primarySourceRoot);

        CallGraph callGraph = new CallGraph();
        long tExtract = System.nanoTime();
        CallGraphExtractor extractor = new CallGraphExtractor(callGraph, customMarkers, resolver);
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();
        log.info("Call graph: {}  [{}]", callGraph, elapsed(tExtract));

        // ------------------------------------------------------------------
        // 3. Run analyses
        // ------------------------------------------------------------------
        long tRisks = System.nanoTime();
        List<TransactionRisk> risks = new TransactionAnalyzer(callGraph).findRisks();
        log.info("Transaction risks: {}  [{}]", risks.size(), elapsed(tRisks));

        long tPaths = System.nanoTime();
        List<PathAnalyzer.LongestPath> longestPaths =
            new PathAnalyzer(callGraph).findLongestPaths(10);
        log.info("Longest paths: top {}  [{}]", longestPaths.size(), elapsed(tPaths));

        long tCoupling = System.nanoTime();
        CouplingAnalyzer couplingAnalyzer = new CouplingAnalyzer(callGraph);
        List<Set<MethodNode>> cyclicClusters = couplingAnalyzer.findCyclicClusters();
        Map<String, CouplingAnalyzer.PackageCoupling> coupling =
            couplingAnalyzer.analysePackageCoupling();
        log.info("Coupling analysis: {} cyclic clusters  [{}]", cyclicClusters.size(), elapsed(tCoupling));

        ConsoleReporter console = new ConsoleReporter(System.out);
        if (printRisks)  console.print(risks);
        if (printPaths)  console.printLongestPaths(longestPaths);
        if (printCycles) console.printCycles(cyclicClusters);

        long tLock = System.nanoTime();
        LockRiskAnalyzer lockAnalyzer = new LockRiskAnalyzer(callGraph);
        List<LockRiskAnalyzer.NestedTxRisk> nestedTxRisks =
            lockAnalyzer.findNestedRequiresNewRisks();
        List<LockRiskAnalyzer.CyclicTxRisk> cyclicTxRisks =
            lockAnalyzer.findCyclicTransactionRisks(cyclicClusters);
        log.info("Lock risks: {} nested-tx, {} cyclic-tx  [{}]",
            nestedTxRisks.size(), cyclicTxRisks.size(), elapsed(tLock));

        if (printLockRisks) console.printLockRisks(nestedTxRisks, cyclicTxRisks);

        // ------------------------------------------------------------------
        // 4. Write reports (parallel — each reporter writes to a separate file)
        // ------------------------------------------------------------------
        Path outPath = Path.of(outputDir);
        Files.createDirectories(outPath);

        Path jsonFile    = outPath.resolve("analysis.json");
        Path dotFile     = outPath.resolve("call-graph.dot");
        Path gexfFile    = outPath.resolve("class-graph.gexf");
        Path htmlFile    = outPath.resolve("class-graph.html");
        Path pkgHtmlFile = outPath.resolve("package-graph.html");

        // Class-level collapse is shared by GEXF and HTML; compute once on the main thread.
        long tCollapse = System.nanoTime();
        ClassGraphModel classModel = ClassGraphModel.collapse(
            callGraph, risks, cyclicClusters, nestedTxRisks, cyclicTxRisks);
        log.info("Class model collapsed: {} classes, {} edges  [{}]",
            classModel.nodes().size(), classModel.edges().size(), elapsed(tCollapse));

        PackageGraphModel pkgModel = PackageGraphModel.collapse(classModel);
        log.info("Package model collapsed: {} packages, {} edges",
            pkgModel.nodes().size(), pkgModel.edges().size());

        List<CompletableFuture<Void>> reportTasks = new ArrayList<>();
        long tReports = System.nanoTime();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {

            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(jsonFile)) {
                    new JsonReporter(callGraph).write(w, risks, longestPaths, cyclicClusters, coupling, nestedTxRisks, cyclicTxRisks);
                }
                log.info("JSON written: {}  [{}]", jsonFile, elapsed(t));
            }), exec));

            // Method-level DOT is opt-in: on a large monolith its SVG is an unreadable
            // hairball, so the interactive class-level exports below are the default.
            if (writeDot) {
                reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                    long t = System.nanoTime();
                    try (var w = Files.newBufferedWriter(dotFile)) {
                        new DotReporter(callGraph, cyclicClusters).writeWithRisks(w, true, risks);
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

        // ------------------------------------------------------------------
        // 5. Console summary
        // ------------------------------------------------------------------
        String reports = String.join("\n           ",
            htmlFile.toAbsolutePath() + " (open in browser)",
            pkgHtmlFile.toAbsolutePath() + " (open in browser)",
            gexfFile.toAbsolutePath() + " (open in Gephi)",
            jsonFile.toAbsolutePath().toString())
            + (writeDot ? "\n           " + dotFile.toAbsolutePath() : "");

        System.out.printf("""
            %n=== Analysis complete ===%n
            Vertices : %d  (application: %d, stubs: %d)
            Edges    : %d
            Tx risks : %d
            Total    : %s%n
            Reports  : %s%n
            """,
            callGraph.vertexCount(),
            callGraph.applicationNodes().size(),
            callGraph.vertexCount() - callGraph.applicationNodes().size(),
            callGraph.edgeCount(),
            risks.size(),
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

    /** Returns elapsed milliseconds since {@code startNano} as a loggable string. */
    private static String elapsed(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000 + " ms";
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
}
