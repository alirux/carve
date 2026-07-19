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
import com.codingful.carve.extractor.UserDefinedMarkers;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.reporter.ConsoleReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI entry point and pipeline orchestrator.
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
 * Command-line parsing lives in {@link CarveConfig}; report generation in
 * {@link ReportWriter}. This class wires the phases together:
 * parse → build Spoon model → extract call graph → analyse → write reports.
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
            config = CarveConfig.parse(args);
        } catch (CarveConfig.UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return; // unreachable; satisfies definite-assignment of config below
        }
        long t0 = System.nanoTime();

        CtModel   model  = buildSpoonModel(config);
        CallGraph cg     = extractCallGraph(model, config);
        Analyses  result = runAnalyses(cg, config);
        ReportWriter.write(cg, result, config);
        printSummary(cg, result, config, t0);
        new ConsoleReporter(System.out).printFooter();
    }

    // -----------------------------------------------------------------------
    // Pipeline phases
    // -----------------------------------------------------------------------

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
        if (callGraph.lombokDetected()) {
            log.warn("Lombok detected on {} type(s): generated members are not modelled, "
                + "so coupling via generated accessors — and especially @Builder chains — "
                + "may be incomplete. See docs/LOMBOK.md.", callGraph.lombokAnnotatedTypeCount());
        }
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

    static void printSummary(CallGraph cg, Analyses r, CarveConfig c, long t0) {
        Path outPath     = Path.of(c.outputDir());
        Path htmlFile    = outPath.resolve("class-graph.html");
        Path pkgHtmlFile = outPath.resolve("package-graph.html");
        Path gexfFile    = outPath.resolve("class-graph.gexf");
        Path jsonFile    = outPath.resolve("analysis.json");
        Path csvFile     = outPath.resolve("class-edges.csv");
        Path dotFile     = outPath.resolve("call-graph.dot");

        String reports = String.join("\n           ",
            htmlFile.toAbsolutePath() + " (open in browser)",
            pkgHtmlFile.toAbsolutePath() + " (open in browser)",
            gexfFile.toAbsolutePath() + " (open in Gephi)",
            jsonFile.toAbsolutePath().toString(),
            csvFile.toAbsolutePath() + " (class edges, one row per coupling)")
            + (c.writeDot() ? "\n           " + dotFile.toAbsolutePath() : "");

        int appCount = cg.applicationNodes().size();
        String lombokNote = cg.lombokDetected()
            ? String.format("%n           ⚠ Lombok detected on %d type(s): coupling via generated "
                + "accessors / @Builder chains may be incomplete (see docs/LOMBOK.md).",
                cg.lombokAnnotatedTypeCount())
            : "";
        System.out.printf("""
            %n=== Analysis complete ===%n
            Vertices : %d  (application: %d, stubs: %d)
            Edges    : %d  (%d inferred by class hierarchy analysis)
            Tx risks : %d
            Total    : %s%n
            Reports  : %s%s%n
            """,
            cg.vertexCount(), appCount, cg.vertexCount() - appCount,
            cg.edgeCount(), cg.chaEdgeCount(),
            r.risks().size(),
            elapsed(t0),
            reports,
            lombokNote
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

    /** Returns elapsed milliseconds since {@code startNano} as a loggable string. */
    private static String elapsed(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000 + " ms";
    }
}
