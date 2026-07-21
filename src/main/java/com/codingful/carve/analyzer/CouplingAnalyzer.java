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

package com.codingful.carve.analyzer;

import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Identifies clusters of application code that are tightly coupled.
 *
 * <h3>Strategies</h3>
 * <ol>
 *   <li><b>Strongly Connected Components (SCC)</b> — groups of methods with
 *       mutual (direct or indirect) call cycles. An SCC with more than one
 *       vertex represents code that cannot be split without breaking cycles.</li>
 *   <li><b>Package coupling</b> — counts cross-package dependency edges to
 *       surface packages with high afferent/efferent coupling.</li>
 * </ol>
 */
public class CouplingAnalyzer {

    private final CallGraph callGraph;

    public CouplingAnalyzer(CallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    // -----------------------------------------------------------------------
    // SCC — cycle detection
    // -----------------------------------------------------------------------

    /**
     * Returns all Strongly Connected Components with more than one method
     * (i.e., actual cycles), sorted descending by size.
     *
     * Only application nodes are considered; library stubs are excluded.
     */
    public List<Set<MethodNode>> findCyclicClusters() {
        // Build a sub-graph containing only application-code nodes
        Set<MethodNode> appNodes = callGraph.applicationNodes();
        var subgraph = new AsSubgraph<>(callGraph.getRaw(), appNodes);

        var inspector = new KosarajuStrongConnectivityInspector<>(subgraph);
        return inspector.stronglyConnectedSets().stream()
            .filter(scc -> scc.size() > 1)
            .sorted(Comparator.<Set<MethodNode>>comparingInt(Set::size).reversed())
            .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Package coupling
    // -----------------------------------------------------------------------

    /**
     * Returns a map of package name → {@link PackageCoupling}.
     * Edges between two methods in the same package are treated as internal;
     * edges crossing a package boundary count toward efferent (Ce) coupling.
     *
     * <p><b>Ambiguous inferred edges are excluded.</b> An edge that CHA created
     * while choosing between several implementations (fan-out &gt; 1) is a genuine
     * over-approximation — a phantom coupling that would otherwise inflate Ca/Ce,
     * instability and the derived hotspots. Exactly-resolved inferred edges
     * (fan-out 1, the interface had a single implementation) are kept: they are
     * sound dependencies, and dropping them would understate the coupling as
     * badly as keeping a phantom overstates it. This filter is coupling-only —
     * the transaction and lock analyses keep every inferred edge, since a risk
     * path worth checking is worth checking regardless of which implementation is
     * wired. See {@code docs/CHA.md} §6b–§7.
     */
    public Map<String, PackageCoupling> analysePackageCoupling() {
        Map<String, PackageCoupling.Builder> builders = new HashMap<>();
        Set<String> presentationOnly = presentationOnlyPackages();

        for (DefaultEdge edge : callGraph.edges()) {
            // Drop the guessed CHA edges; keep direct and exactly-resolved ones.
            if (callGraph.isChaEdge(edge) && callGraph.chaFanOut(edge) > 1) continue;

            MethodNode source = callGraph.getRaw().getEdgeSource(edge);
            MethodNode target = callGraph.getRaw().getEdgeTarget(edge);

            if (!source.isApplicationCode()) continue;

            String srcPkg = source.getPackageName();
            String tgtPkg = target.getPackageName();

            // The source is application code, so its package is too.
            builders.computeIfAbsent(srcPkg, PackageCoupling.Builder::new)
                    .markApplicationCode();

            if (!srcPkg.equals(tgtPkg)) {
                // srcPkg depends on tgtPkg → efferent for src, afferent for tgt
                builders.get(srcPkg).addEfferent(tgtPkg);
                var tgtBuilder = builders.computeIfAbsent(tgtPkg, PackageCoupling.Builder::new)
                        .addAfferent(srcPkg);
                if (target.isApplicationCode()) tgtBuilder.markApplicationCode();
            }
        }

        return builders.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue()
                      .markPresentationOnly(presentationOnly.contains(e.getKey()))
                      .build()));
    }

    /**
     * Packages whose Spring-managed components are <em>all</em> presentation —
     * at least one {@code @Controller}/{@code @RestController} and no
     * service/repository/other business component. Classes with no Spring
     * stereotype (DTOs, plain helpers) do not disqualify a package: a controller
     * package naturally carries request/response types.
     *
     * <p>Such a package is the edge of the system, not a bounded context. It has
     * low afferent coupling <i>by construction</i> — nothing depends on a
     * controller — so it scores as an {@link Archetype#EXTRACTION_CANDIDATE}
     * without being one. {@link #classify} uses this to keep it off that list.
     */
    private Set<String> presentationOnlyPackages() {
        // pkg → [sawController, sawOtherStereotype]
        Map<String, boolean[]> seen = new HashMap<>();
        for (MethodNode n : callGraph.applicationNodes()) {
            SpringComponentType type = n.getComponentType();
            if (type == SpringComponentType.NONE) continue;
            boolean[] flags = seen.computeIfAbsent(n.getPackageName(), k -> new boolean[2]);
            if (type == SpringComponentType.CONTROLLER
                    || type == SpringComponentType.REST_CONTROLLER) {
                flags[0] = true;
            } else {
                flags[1] = true;
            }
        }
        return seen.entrySet().stream()
            .filter(e -> e.getValue()[0] && !e.getValue()[1])
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------------
    // Coupling hotspots — actionable classification for modernisation
    // -----------------------------------------------------------------------

    /**
     * Thresholds that turn raw Ca/Ce/I numbers into the three modernisation
     * archetypes. They are deliberately conservative so that only clear-cut
     * cases are flagged; packages sitting near the "main sequence" (balanced
     * Ca/Ce) are intentionally left unclassified.
     */
    public static final int    HUB_MIN_CA               = 5;
    public static final double UNSTABLE_MIN_INSTABILITY = 0.70;
    public static final int    LEAF_MAX_CA              = 3;
    public static final int    EXTRACTABLE_MIN_CE       = 5;
    public static final double STABLE_MAX_INSTABILITY   = 0.30;

    /**
     * Classifies every application package into at most one modernisation
     * archetype (see {@link Archetype}). Library/JDK packages are excluded —
     * they are stable by definition and are never refactoring targets.
     *
     * <p>The three archetypes are mutually exclusive by construction: an
     * {@code UNSTABLE_HUB} requires high instability while a {@code STABLE_CORE}
     * requires low instability, and an {@code EXTRACTION_CANDIDATE} requires low
     * afferent coupling while the two hub-shaped archetypes require high.</p>
     */
    public static CouplingHotspots classifyHotspots(Collection<PackageCoupling> packages) {
        List<PackageHotspot> unstableHubs          = new ArrayList<>();
        List<PackageHotspot> extractionCandidates  = new ArrayList<>();
        List<PackageHotspot> stableCores           = new ArrayList<>();

        for (PackageCoupling p : packages) {
            if (!p.applicationCode()) continue;

            Archetype archetype = classify(p);
            if (archetype == null) continue;

            (switch (archetype) {
                case UNSTABLE_HUB        -> unstableHubs;
                case EXTRACTION_CANDIDATE -> extractionCandidates;
                case STABLE_CORE         -> stableCores;
            }).add(PackageHotspot.of(p, archetype));
        }

        Comparator<PackageHotspot> byScore =
            Comparator.comparingDouble(PackageHotspot::score).reversed();
        unstableHubs.sort(byScore);
        extractionCandidates.sort(byScore);
        stableCores.sort(byScore);

        return new CouplingHotspots(unstableHubs, extractionCandidates, stableCores);
    }

    private static Archetype classify(PackageCoupling p) {
        int    ca = p.ca();
        int    ce = p.ce();
        double i  = p.instability();

        if (ca >= HUB_MIN_CA && i >= UNSTABLE_MIN_INSTABILITY) {
            return Archetype.UNSTABLE_HUB;
        }
        if (ca <= LEAF_MAX_CA && i >= UNSTABLE_MIN_INSTABILITY && ce >= EXTRACTABLE_MIN_CE) {
            // A presentation-only package is a leaf by construction (nothing calls
            // a controller), not a self-contained bounded context. Leave it
            // unclassified rather than rank it as easy to peel off.
            return p.presentationOnly() ? null : Archetype.EXTRACTION_CANDIDATE;
        }
        if (ca >= HUB_MIN_CA && i <= STABLE_MAX_INSTABILITY) {
            return Archetype.STABLE_CORE;
        }
        return null;
    }

    /**
     * The three modernisation archetypes derived from the Ca/Ce/instability
     * triple. Each carries the design principle it embodies and the JSON key
     * under which it is reported.
     */
    public enum Archetype {
        /**
         * High afferent coupling <i>and</i> high instability: many packages
         * depend on it, yet it depends on many in turn — a violation of the
         * Stable Dependencies Principle and the primary bottleneck to untangle.
         */
        UNSTABLE_HUB("unstableHubs"),
        /**
         * Low afferent coupling but high instability with a substantial
         * efferent surface: few depend on it, so it can be peeled off as a
         * separate service/module with a low blast radius.
         */
        EXTRACTION_CANDIDATE("extractionCandidates"),
        /**
         * High afferent coupling and low instability: heavily depended on and
         * already stable — a shared kernel to protect behind explicit ports
         * rather than rewrite.
         */
        STABLE_CORE("stableCores");

        private final String jsonKey;

        Archetype(String jsonKey) { this.jsonKey = jsonKey; }

        /** Key under which packages of this archetype are grouped in the JSON report. */
        public String jsonKey() { return jsonKey; }
    }

    // -----------------------------------------------------------------------
    // Value objects
    // -----------------------------------------------------------------------

    public record PackageCoupling(
        String packageName,
        /** Packages that this package depends on (outgoing). */
        Set<String> efferentDependencies,
        /** Packages that depend on this package (incoming). */
        Set<String> afferentDependencies,
        /** {@code true} when the package belongs to the analysed source (not a library/JDK stub). */
        boolean applicationCode,
        /**
         * {@code true} when every Spring-managed component in the package is a
         * controller — a presentation leaf that is the edge of the system rather
         * than an extractable bounded context. See {@link #presentationOnlyPackages()}.
         */
        boolean presentationOnly
    ) {
        /** Backwards-compatible constructor for a package with no presentation flag. */
        public PackageCoupling(String packageName,
                               Set<String> efferentDependencies,
                               Set<String> afferentDependencies,
                               boolean applicationCode) {
            this(packageName, efferentDependencies, afferentDependencies, applicationCode, false);
        }

        /** Ce — efferent coupling count */
        public int ce() { return efferentDependencies.size(); }
        /** Ca — afferent coupling count */
        public int ca() { return afferentDependencies.size(); }

        /**
         * Instability I = Ce / (Ca + Ce).
         * 0 = maximally stable (many dependents, no outgoing deps).
         * 1 = maximally unstable (no dependents, all outgoing deps).
         */
        public double instability() {
            int total = ca() + ce();
            return total == 0 ? 0.0 : (double) ce() / total;
        }

        static class Builder {
            private final String pkg;
            private final Set<String> efferent = new HashSet<>();
            private final Set<String> afferent  = new HashSet<>();
            private boolean applicationCode = false;
            private boolean presentationOnly = false;

            Builder(String pkg) { this.pkg = pkg; }

            Builder addEfferent(String p) { efferent.add(p); return this; }
            Builder addAfferent(String p) { afferent.add(p); return this; }
            Builder markApplicationCode() { this.applicationCode = true; return this; }
            Builder markPresentationOnly(boolean v) { this.presentationOnly = v; return this; }

            PackageCoupling build() {
                return new PackageCoupling(
                    pkg, Set.copyOf(efferent), Set.copyOf(afferent), applicationCode, presentationOnly);
            }
        }
    }

    /**
     * A single application package flagged as a modernisation hotspot, together
     * with the {@link Archetype} it was classified as and a {@code score} that
     * ranks its importance <i>within</i> that archetype (higher = more important).
     *
     * <p>The score is archetype-specific so the three lists can each be sorted
     * by the metric that matters for that principle:</p>
     * <ul>
     *   <li>{@code UNSTABLE_HUB} → {@code Ca · I} (bottleneck severity)</li>
     *   <li>{@code EXTRACTION_CANDIDATE} → {@code Ce · I} (extractable surface × unstableness)</li>
     *   <li>{@code STABLE_CORE} → {@code Ca · (1 − I)} (load-bearing stability)</li>
     * </ul>
     *
     * <p>The score is a <i>relative</i> ranking, not a normalised 0–1 value: it
     * scales with {@code Ca}/{@code Ce} and is therefore <b>unbounded above</b>,
     * and is only meaningful when compared within the same archetype. Given the
     * classification thresholds the effective minimum is {@code 3.5}
     * ({@code HUB_MIN_CA × UNSTABLE_MIN_INSTABILITY}) for every archetype.</p>
     */
    public record PackageHotspot(
        String packageName,
        Archetype archetype,
        int afferentCa,
        int efferentCe,
        double instability,
        double score
    ) {
        static PackageHotspot of(PackageCoupling p, Archetype archetype) {
            double i = p.instability();
            double score = switch (archetype) {
                case UNSTABLE_HUB        -> p.ca() * i;
                case EXTRACTION_CANDIDATE -> p.ce() * i;
                case STABLE_CORE         -> p.ca() * (1.0 - i);
            };
            return new PackageHotspot(p.packageName(), archetype, p.ca(), p.ce(), i, score);
        }
    }

    /**
     * The actionable view over package coupling: application packages grouped
     * into the three modernisation archetypes, each list sorted by descending
     * {@link PackageHotspot#score()}.
     */
    public record CouplingHotspots(
        List<PackageHotspot> unstableHubs,
        List<PackageHotspot> extractionCandidates,
        List<PackageHotspot> stableCores
    ) {
        /** All classified hotspots across the three archetypes, in no particular order. */
        public List<PackageHotspot> all() {
            List<PackageHotspot> all = new ArrayList<>(
                unstableHubs.size() + extractionCandidates.size() + stableCores.size());
            all.addAll(unstableHubs);
            all.addAll(extractionCandidates);
            all.addAll(stableCores);
            return all;
        }

        /** Index of {@link PackageHotspot} by package name, for graph tagging. */
        public Map<String, PackageHotspot> byPackage() {
            return all().stream().collect(Collectors.toMap(
                PackageHotspot::packageName, h -> h, (a, b) -> a, LinkedHashMap::new));
        }
    }
}
