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

import com.codingful.carve.analyzer.CouplingAnalyzer.Archetype;
import com.codingful.carve.analyzer.CouplingAnalyzer.CouplingHotspots;
import com.codingful.carve.analyzer.CouplingAnalyzer.PackageCoupling;
import com.codingful.carve.analyzer.CouplingAnalyzer.PackageHotspot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CouplingAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a PackageCoupling with exactly {@code ca} afferent and {@code ce} efferent deps. */
    private static PackageCoupling pkg(String name, int ca, int ce, boolean applicationCode) {
        Set<String> afferent = names("a", ca);
        Set<String> efferent = names("e", ce);
        return new PackageCoupling(name, efferent, afferent, applicationCode);
    }

    private static Set<String> names(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).collect(Collectors.toSet());
    }

    private static List<String> packageNames(List<PackageHotspot> hotspots) {
        return hotspots.stream().map(PackageHotspot::packageName).toList();
    }

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    @Test
    void unstableHub_highCa_highInstability() {
        // Ca=15, Ce=53 → I≈0.78 : many depend on it yet it depends on everything.
        var hubs = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.bo", 15, 53, true))).unstableHubs();

        assertThat(packageNames(hubs)).containsExactly("app.bo");
        assertThat(hubs.get(0).archetype()).isEqualTo(Archetype.UNSTABLE_HUB);
    }

    @Test
    void extractionCandidate_lowCa_highInstability_substantialCe() {
        // Ca=2, Ce=34 → I≈0.94 : few depend on it, large efferent surface.
        var candidates = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.synch", 2, 34, true))).extractionCandidates();

        assertThat(packageNames(candidates)).containsExactly("app.synch");
        assertThat(candidates.get(0).archetype()).isEqualTo(Archetype.EXTRACTION_CANDIDATE);
    }

    @Test
    void stableCore_highCa_lowInstability() {
        // Ca=17, Ce=7 → I≈0.29 : heavily depended on and already stable.
        var cores = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.dao", 17, 7, true))).stableCores();

        assertThat(packageNames(cores)).containsExactly("app.dao");
        assertThat(cores.get(0).archetype()).isEqualTo(Archetype.STABLE_CORE);
    }

    @Test
    void balancedPackage_onMainSequence_isNotClassified() {
        // Ca=19, Ce=17 → I≈0.47 : neither hub, nor stable core, nor extractable.
        CouplingHotspots h = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.model", 19, 17, true)));

        assertThat(h.unstableHubs()).isEmpty();
        assertThat(h.extractionCandidates()).isEmpty();
        assertThat(h.stableCores()).isEmpty();
    }

    @Test
    void libraryPackages_areExcluded() {
        // java.lang shape: Ca=31, Ce=0, but not application code.
        CouplingHotspots h = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("java.lang", 31, 0, false)));

        assertThat(h.stableCores()).isEmpty();
        assertThat(h.unstableHubs()).isEmpty();
        assertThat(h.extractionCandidates()).isEmpty();
    }

    @Test
    void tinyUnstableLeaf_belowEfferentThreshold_isNotExtractionCandidate() {
        // Ca=0, Ce=2 → high instability but too small to be worth extracting.
        var candidates = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.tiny", 0, 2, true))).extractionCandidates();

        assertThat(candidates).isEmpty();
    }

    @Test
    void eachListIsSortedByDescendingScore() {
        var hubs = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.small", 5, 12, true),   // Ca=5,  I≈0.71 → score ≈ 3.5
            pkg("app.big",   15, 53, true)   // Ca=15, I≈0.78 → score ≈ 11.7
        )).unstableHubs();

        assertThat(packageNames(hubs)).containsExactly("app.big", "app.small");
        assertThat(hubs.get(0).score()).isGreaterThan(hubs.get(1).score());
    }

    @Test
    void archetypesArePartitioned_oneMixedInput() {
        CouplingHotspots h = CouplingAnalyzer.classifyHotspots(List.of(
            pkg("app.bo",    15, 53, true),  // unstable hub
            pkg("app.synch",  2, 34, true),  // extraction candidate
            pkg("app.dao",   17,  7, true),  // stable core
            pkg("app.model", 19, 17, true),  // balanced → unclassified
            pkg("java.util", 30,  0, false)  // library → excluded
        ));

        assertThat(packageNames(h.unstableHubs())).containsExactly("app.bo");
        assertThat(packageNames(h.extractionCandidates())).containsExactly("app.synch");
        assertThat(packageNames(h.stableCores())).containsExactly("app.dao");
    }
}
