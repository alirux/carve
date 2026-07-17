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

import com.codingful.carve.analyzer.LockRiskAnalyzer;
import com.codingful.carve.analyzer.PathAnalyzer;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prints transaction risks to stdout as indented call-stack trees.
 *
 * <pre>
 * Transaction risks: 2
 *
 * ──────────────────────────────────────────────────────────────
 * Risk 1/2  [HTTP]
 * ──────────────────────────────────────────────────────────────
 * OrderService.checkout()                  @Transactional
 * └─► OrderServiceImpl.process()           @Transactional
 *       └─► OrderServiceImpl.finalize()
 *             └─► PaymentClient.charge()   ← HTTP
 * </pre>
 */
public class ConsoleReporter {

    private static final String DIVIDER =
        "──────────────────────────────────────────────────────────────";
    private static final String CONNECTOR = "└─► ";
    private static final String INDENT    = "      ";

    private final PrintStream out;

    public ConsoleReporter(PrintStream out) {
        this.out = out;
    }

    public void print(List<TransactionRisk> risks) {
        out.println();
        out.printf("Transaction risks: %d%n", risks.size());

        if (risks.isEmpty()) {
            out.println("  (none found)");
            return;
        }

        for (int i = 0; i < risks.size(); i++) {
            printRisk(risks.get(i), i + 1, risks.size());
        }
    }

    /** Prints the provenance footer — tool version, licence, source, and no-warranty disclaimer. */
    public void printFooter() {
        out.println();
        out.println(DIVIDER);
        out.println(ReportMetadata.generatedByLine());
        out.println(ReportMetadata.toolCreditLine());
        out.println(ReportMetadata.DISCLAIMER);
        out.println(DIVIDER);
    }

    private void printRisk(TransactionRisk risk, int index, int total) {
        String types = risk.callTypes().stream()
            .map(ExternalCallType::name)
            .collect(Collectors.joining(", "));

        out.println();
        out.println(DIVIDER);
        out.printf("Risk %d/%d  [%s]%n", index, total, types);
        out.println(DIVIDER);

        List<MethodNode> path = risk.path();
        for (int i = 0; i < path.size(); i++) {
            MethodNode node  = path.get(i);
            boolean isLast   = (i == path.size() - 1);
            String indent    = INDENT.repeat(i);
            String connector = (i == 0) ? "" : CONNECTOR;
            String label     = projectLabel(node) + node.getClassName() + "." + node.getMethodName() + "()";
            String suffix    = buildSuffix(node, isLast, risk);

            out.printf("%s%s%-60s%s%n", indent, connector, label, suffix);
        }
    }

    public void printCycles(List<Set<MethodNode>> clusters) {
        out.println();
        out.printf("Cyclic clusters: %d  (methods that cannot be split without breaking call cycles)%n",
            clusters.size());

        if (clusters.isEmpty()) {
            out.println("  (none found)");
            return;
        }

        for (int i = 0; i < clusters.size(); i++) {
            Set<MethodNode> cluster = clusters.get(i);
            out.println();
            out.println(DIVIDER);
            out.printf("Cluster %d/%d  (%d methods)%n", i + 1, clusters.size(), cluster.size());
            out.println(DIVIDER);

            cluster.stream()
                .sorted(Comparator.comparing(n -> n.getClassName() + "." + n.getMethodName()))
                .forEach(node -> {
                    String label  = "  " + projectLabel(node) + node.getClassName() + "." + node.getMethodName() + "()";
                    String suffix = buildNodeSuffix(node);
                    out.printf("%-62s%s%n", label, suffix);
                });
        }
    }

    public void printLongestPaths(List<PathAnalyzer.LongestPath> paths) {
        out.println();
        out.printf("Longest call chains: %d%n", paths.size());

        if (paths.isEmpty()) {
            out.println("  (none found)");
            return;
        }

        for (int i = 0; i < paths.size(); i++) {
            PathAnalyzer.LongestPath lp = paths.get(i);
            out.println();
            out.println(DIVIDER);
            out.printf("Path %d/%d  (depth: %d)%n", i + 1, paths.size(), lp.depth());
            out.println(DIVIDER);

            List<MethodNode> nodes = lp.nodes();
            for (int j = 0; j < nodes.size(); j++) {
                MethodNode node  = nodes.get(j);
                String indent    = INDENT.repeat(j);
                String connector = (j == 0) ? "" : CONNECTOR;
                String label     = projectLabel(node) + node.getClassName() + "." + node.getMethodName() + "()";
                String suffix    = buildNodeSuffix(node);
                out.printf("%s%s%-60s%s%n", indent, connector, label, suffix);
            }
        }
    }

    public void printLockRisks(List<LockRiskAnalyzer.NestedTxRisk> nested,
                               List<LockRiskAnalyzer.CyclicTxRisk> cyclic) {
        out.println();
        out.printf("Lock risks — nested REQUIRES_NEW: %d%n", nested.size());

        if (nested.isEmpty()) {
            out.println("  (none found)");
        } else {
            for (int i = 0; i < nested.size(); i++) {
                LockRiskAnalyzer.NestedTxRisk r = nested.get(i);
                out.println();
                out.println(DIVIDER);
                out.printf("Nested-tx %d/%d%n", i + 1, nested.size());
                out.println(DIVIDER);
                List<MethodNode> path = r.path();
                for (int j = 0; j < path.size(); j++) {
                    MethodNode node = path.get(j);
                    String indent    = INDENT.repeat(j);
                    String connector = j == 0 ? "" : CONNECTOR;
                    String label     = projectLabel(node) + node.getClassName() + "." + node.getMethodName() + "()";
                    String suffix    = node.isTransactional()
                        ? "  @Transactional" + (node.getPropagation() != com.codingful.carve.model.TransactionPropagation.REQUIRED
                            ? "(" + node.getPropagation() + ")" : "")
                        : "";
                    out.printf("%s%s%-60s%s%n", indent, connector, label, suffix);
                }
            }
        }

        out.println();
        out.printf("Lock risks — cyclic @Transactional clusters: %d%n", cyclic.size());

        if (cyclic.isEmpty()) {
            out.println("  (none found)");
        } else {
            for (int i = 0; i < cyclic.size(); i++) {
                LockRiskAnalyzer.CyclicTxRisk r = cyclic.get(i);
                out.println();
                out.println(DIVIDER);
                out.printf("Cyclic-tx %d/%d  (%d methods, %d @Transactional)%n",
                    i + 1, cyclic.size(), r.cluster().size(), r.transactionalNodes().size());
                out.println(DIVIDER);
                r.cluster().stream()
                    .sorted(Comparator.comparing(n -> n.getClassName() + "." + n.getMethodName()))
                    .forEach(node -> {
                        boolean isTx = r.transactionalNodes().contains(node);
                        String label  = "  " + projectLabel(node) + node.getClassName() + "." + node.getMethodName() + "()";
                        String suffix = isTx ? "  @Transactional ⚠" : "";
                        out.printf("%-62s%s%n", label, suffix);
                    });
            }
        }
    }

    private String buildNodeSuffix(MethodNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.isTransactional()) {
            sb.append("  @Transactional");
            if (node.isReadOnly()) sb.append("(readOnly)");
        }
        if (node.makesExternalCall()) {
            String types = node.getExternalCalls().stream()
                .map(ExternalCallType::name)
                .collect(Collectors.joining(", "));
            sb.append("  [").append(types).append("]");
        }
        return sb.toString();
    }

    private static String projectLabel(MethodNode node) {
        String p = node.getProjectName();
        return p.isEmpty() ? "" : "[" + p + "] ";
    }

    private String buildSuffix(MethodNode node, boolean isLast, TransactionRisk risk) {
        StringBuilder sb = new StringBuilder();

        if (node.isTransactional()) {
            sb.append("  @Transactional");
            if (node.isReadOnly()) sb.append("(readOnly)");
        }

        if (isLast) {
            String types = risk.callTypes().stream()
                .map(ExternalCallType::name)
                .collect(Collectors.joining(", "));
            sb.append("  ← ").append(types);
        }

        return sb.toString();
    }
}
