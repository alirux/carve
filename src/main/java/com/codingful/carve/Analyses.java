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
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.model.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregated results of the analysis phase, consumed by report writing and the summary. */
record Analyses(
    List<TransactionRisk> risks,
    List<PathAnalyzer.LongestPath> longestPaths,
    List<Set<MethodNode>> cyclicClusters,
    Map<String, CouplingAnalyzer.PackageCoupling> coupling,
    List<LockRiskAnalyzer.NestedTxRisk> nestedTxRisks,
    List<LockRiskAnalyzer.CyclicTxRisk> cyclicTxRisks
) {}
