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
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.reporter.ClassGraphModel;
import com.codingful.carve.reporter.CsvReporter;
import com.codingful.carve.reporter.DotReporter;
import com.codingful.carve.reporter.GexfReporter;
import com.codingful.carve.reporter.HtmlReporter;
import com.codingful.carve.reporter.JsonReporter;
import com.codingful.carve.reporter.PackageGraphModel;
import com.codingful.carve.reporter.PackageHtmlReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * Writes every analysis report into the output directory.
 *
 * <p>The class-level collapse (shared by GEXF and HTML) is computed once on the
 * calling thread; the individual file exports then run in parallel on virtual
 * threads. The method blocks until all reports are flushed.
 */
final class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    private ReportWriter() { }

    static void write(CallGraph cg, Analyses r, CarveConfig c) throws IOException {
        Path outPath = Path.of(c.outputDir());
        Files.createDirectories(outPath);

        Path jsonFile    = outPath.resolve("analysis.json");
        Path dotFile     = outPath.resolve("call-graph.dot");
        Path gexfFile    = outPath.resolve("class-graph.gexf");
        Path csvFile     = outPath.resolve("class-edges.csv");
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

            // The class graph in a form that stays small and greppable: the GEXF
            // carries the same edges in far more XML, and analysis.json carries none.
            reportTasks.add(CompletableFuture.runAsync(ioTask(() -> {
                long t = System.nanoTime();
                try (var w = Files.newBufferedWriter(csvFile)) {
                    new CsvReporter().write(w, classModel);
                }
                log.info("CSV written: {} ({} edges)  [{}]",
                    csvFile, classModel.edges().size(), elapsed(t));
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

    /** Returns elapsed milliseconds since {@code startNano} as a loggable string. */
    private static String elapsed(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000 + " ms";
    }
}
