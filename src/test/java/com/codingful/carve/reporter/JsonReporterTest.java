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

package com.codingful.carve.reporter;

import com.codingful.carve.analyzer.CouplingAnalyzer.PackageCoupling;
import com.codingful.carve.analyzer.LockRiskAnalyzer.CyclicTxRisk;
import com.codingful.carve.analyzer.LockRiskAnalyzer.NestedTxRisk;
import com.codingful.carve.analyzer.PathAnalyzer.LongestPath;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.codingful.carve.support.TestNodes.app;
import static com.codingful.carve.support.TestNodes.lib;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code analysis.json} contract by deserialising the output into
 * typed DTOs and asserting on their accessors — so each JSON field name is
 * declared exactly once (as a record component) and checked by the compiler,
 * rather than scattered as string keys across the assertions.
 */
class JsonReporterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Report write(CallGraph cg,
                         List<TransactionRisk> risks,
                         List<LongestPath> paths,
                         List<Set<MethodNode>> clusters,
                         Map<String, PackageCoupling> coupling,
                         List<NestedTxRisk> nested,
                         List<CyclicTxRisk> cyclic) throws IOException {
        StringWriter w = new StringWriter();
        new JsonReporter(cg).write(w, risks, paths, clusters, coupling, nested, cyclic);
        return MAPPER.readValue(w.toString(), Report.class);
    }

    private Report writeEmpty(CallGraph cg) throws IOException {
        return write(cg, List.of(), List.of(), List.of(), Map.of(), List.of(), List.of());
    }

    private static MethodNode appIn(String pkg, String id) {
        return app(id).pkg(pkg).build();
    }

    /** A PackageCoupling with exactly {@code ca} afferent and {@code ce} efferent deps. */
    private static PackageCoupling pkg(String name, int ca, int ce) {
        return new PackageCoupling(name, names("e", ce), names("a", ca), true);
    }

    private static Set<String> names(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    // Structure & summary
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_no_findings_WHEN_writing_json_THEN_all_top_level_sections_are_present() throws IOException {
        CallGraph cg = new CallGraph();
        cg.addVertex(appIn("app", "a"));

        Report report = writeEmpty(cg);

        assertThat(report.summary()).isNotNull();
        assertThat(report.transactionRisks()).isEmpty();
        assertThat(report.longestPaths()).isEmpty();
        assertThat(report.cyclicClusters()).isEmpty();
        assertThat(report.packageCoupling().hotspots()).isNotNull();
        assertThat(report.lockRisks().nestedRequiresNew()).isEmpty();
        assertThat(report.lockRisks().cyclicTransactional()).isEmpty();
    }

    @Test
    void GIVEN_a_graph_WHEN_writing_json_THEN_meta_attributes_the_tool_with_version_license_repo_and_disclaimer()
            throws IOException {
        CallGraph cg = new CallGraph();
        cg.addVertex(appIn("app", "a"));

        Meta meta = writeEmpty(cg).meta();

        assertThat(meta.generatedBy()).isEqualTo(ReportMetadata.TOOL_NAME);
        assertThat(meta.version()).isEqualTo(ReportMetadata.version());
        assertThat(meta.generatedOn()).isEqualTo(ReportMetadata.generatedOn().toString());
        assertThat(meta.toolCopyright()).contains(ReportMetadata.TOOL_AUTHOR);
        assertThat(meta.toolLicense()).isEqualTo(ReportMetadata.TOOL_LICENSE_SPDX);
        assertThat(meta.toolLicenseUrl()).isEqualTo(ReportMetadata.TOOL_LICENSE_URL);
        assertThat(meta.toolRepository()).isEqualTo(ReportMetadata.TOOL_REPOSITORY);
        assertThat(meta.disclaimer()).isEqualTo(ReportMetadata.DISCLAIMER);
    }

    @Test
    void GIVEN_a_graph_WHEN_writing_json_THEN_summary_reports_node_and_edge_counts() throws IOException {
        CallGraph cg = new CallGraph();
        MethodNode tx = app("a").pkg("app.web").transactional().build();
        cg.addEdge(tx, appIn("app.svc", "b"));
        cg.addVertex(lib("ext").build()); // library stub

        Summary summary = writeEmpty(cg).summary();

        assertThat(summary.totalVertices()).isEqualTo(3);
        assertThat(summary.totalEdges()).isEqualTo(1);
        assertThat(summary.applicationMethods()).isEqualTo(2);
        assertThat(summary.transactionalMethods()).isEqualTo(1);
        assertThat(summary.libraryStubs()).isEqualTo(1);
    }

    @Test
    void GIVEN_a_multi_project_graph_WHEN_writing_json_THEN_summary_lists_per_project_counts()
            throws IOException {
        CallGraph cg = new CallGraph();
        cg.addVertex(app("a1").project("api").build());
        cg.addVertex(app("a2").project("api").build());
        cg.addVertex(app("w1").project("worker").build());

        Summary summary = writeEmpty(cg).summary();

        assertThat(summary.projects()).containsEntry("api", 2).containsEntry("worker", 1);
    }

    @Test
    void GIVEN_a_single_project_graph_WHEN_writing_json_THEN_summary_omits_the_projects_field()
            throws IOException {
        CallGraph cg = new CallGraph();
        cg.addVertex(appIn("app", "a"));

        assertThat(writeEmpty(cg).summary().projects()).isNull();
    }

    // -----------------------------------------------------------------------
    // Findings sections
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_transaction_risk_WHEN_writing_json_THEN_root_site_types_and_path_are_serialised()
            throws IOException {
        MethodNode root = app("root").calls(ExternalCallType.HTTP).build();
        MethodNode site = app("site").calls(ExternalCallType.HTTP).build();
        var risk = new TransactionRisk(root, site, Set.of(ExternalCallType.HTTP), List.of(root, site));

        List<Risk> risks = write(new CallGraph(), List.of(risk),
            List.of(), List.of(), Map.of(), List.of(), List.of()).transactionRisks();

        assertThat(risks).hasSize(1);
        Risk r = risks.get(0);
        assertThat(r.transactionalRoot()).isEqualTo("root");
        assertThat(r.externalCallSite()).isEqualTo("site");
        assertThat(r.callTypes()).containsExactly("HTTP");
        assertThat(r.path()).containsExactly("root", "site");
    }

    @Test
    void GIVEN_a_longest_path_WHEN_writing_json_THEN_depth_and_path_are_serialised() throws IOException {
        var path = new LongestPath(List.of(appIn("app", "a"), appIn("app", "b"), appIn("app", "c")));

        List<PathDto> paths = write(new CallGraph(), List.of(), List.of(path),
            List.of(), Map.of(), List.of(), List.of()).longestPaths();

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).depth()).isEqualTo(3);
        assertThat(paths.get(0).path()).containsExactly("a", "b", "c");
    }

    @Test
    void GIVEN_a_cyclic_cluster_WHEN_writing_json_THEN_size_and_sorted_methods_are_serialised()
            throws IOException {
        Set<MethodNode> cluster = Set.of(appIn("app", "z"), appIn("app", "a"), appIn("app", "m"));

        List<Cluster> clusters = write(new CallGraph(), List.of(), List.of(), List.of(cluster),
            Map.of(), List.of(), List.of()).cyclicClusters();

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).size()).isEqualTo(3);
        assertThat(clusters.get(0).methods()).containsExactly("a", "m", "z"); // sorted
    }

    @Test
    void GIVEN_lock_risks_WHEN_writing_json_THEN_nested_and_cyclic_sections_are_serialised()
            throws IOException {
        MethodNode outer = app("outer").build();
        MethodNode inner = app("inner").build();
        var nested = new NestedTxRisk(outer, inner, List.of(outer, inner));
        var cyclic = new CyclicTxRisk(Set.of(outer, inner), Set.of(outer));

        LockRisks lockRisks = write(new CallGraph(), List.of(), List.of(), List.of(),
            Map.of(), List.of(nested), List.of(cyclic)).lockRisks();

        NestedDto n = lockRisks.nestedRequiresNew().get(0);
        assertThat(n.outerRoot()).isEqualTo("outer");
        assertThat(n.requiresNewSite()).isEqualTo("inner");

        CyclicDto c = lockRisks.cyclicTransactional().get(0);
        assertThat(c.clusterSize()).isEqualTo(2);
        assertThat(c.transactionalCount()).isEqualTo(1);
        assertThat(c.transactionalMethods()).containsExactly("outer");
    }

    // -----------------------------------------------------------------------
    // Package coupling & hotspots
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_packages_WHEN_writing_json_THEN_they_are_sorted_by_descending_instability()
            throws IOException {
        Map<String, PackageCoupling> coupling = Map.of(
            "app.stable",   pkg("app.stable",   3, 0),  // I = 0.0
            "app.unstable", pkg("app.unstable", 0, 3)); // I = 1.0

        List<Pkg> packages = write(new CallGraph(), List.of(), List.of(), List.of(),
            coupling, List.of(), List.of()).packageCoupling().packages();

        assertThat(packages).hasSize(2);
        assertThat(packages.get(0).pkg()).isEqualTo("app.unstable");
        assertThat(packages.get(0).instability()).isEqualTo(1.0);
        assertThat(packages.get(1).pkg()).isEqualTo("app.stable");
    }

    @Test
    void GIVEN_a_hub_package_WHEN_writing_json_THEN_it_appears_under_hotspots_unstable_hubs()
            throws IOException {
        // Ca=5, Ce=12 → I≈0.71 : high afferent coupling and high instability.
        Map<String, PackageCoupling> coupling = Map.of("app.bo", pkg("app.bo", 5, 12));

        Hotspots hotspots = write(new CallGraph(), List.of(), List.of(), List.of(),
            coupling, List.of(), List.of()).packageCoupling().hotspots();

        assertThat(hotspots.unstableHubs()).extracting(Hotspot::pkg).containsExactly("app.bo");
        assertThat(hotspots.extractionCandidates()).isEmpty();
        assertThat(hotspots.stableCores()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Typed view of analysis.json — the field names live here, once.
    // ignoreUnknown keeps each DTO scoped to the fields under test.
    // -----------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Report(Meta meta,
                  Summary summary,
                  List<Risk> transactionRisks,
                  List<PathDto> longestPaths,
                  List<Cluster> cyclicClusters,
                  Coupling packageCoupling,
                  LockRisks lockRisks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(String generatedBy, String version, String generatedOn, String toolCopyright,
                String toolLicense, String toolLicenseUrl, String toolRepository, String disclaimer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(int totalVertices, int totalEdges, int applicationMethods,
                   int transactionalMethods, int libraryStubs, Map<String, Integer> projects) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Risk(String transactionalRoot, String externalCallSite,
                List<String> callTypes, List<String> path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathDto(int depth, List<String> path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cluster(int size, List<String> methods) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Coupling(Hotspots hotspots, List<Pkg> packages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotspots(List<Hotspot> unstableHubs,
                    List<Hotspot> extractionCandidates,
                    List<Hotspot> stableCores) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotspot(@JsonProperty("package") String pkg) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pkg(@JsonProperty("package") String pkg, double instability) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LockRisks(List<NestedDto> nestedRequiresNew, List<CyclicDto> cyclicTransactional) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NestedDto(String outerRoot, String requiresNewSite, List<String> path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CyclicDto(int clusterSize, int transactionalCount,
                     List<String> transactionalMethods, List<String> allMethods) {}
}
