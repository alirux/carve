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

import java.io.PrintWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * Exports the class-level edges as CSV.
 *
 * <p>The class graph otherwise exists only in the GEXF, whose XML is an order of
 * magnitude larger and awkward to read selectively. {@code analysis.json} stays
 * compact by carrying findings rather than the graph, so neither report answers
 * "which classes couple these two modules, and is the edge real?" at a size that
 * fits in a spreadsheet, a {@code grep}, or an LLM context window. This one does.
 *
 * <p>One row per class-to-class edge:
 *
 * <pre>
 * source,sourceProject,target,targetProject,weight,chaWeight,implFanOut,edgeKind
 * com.demo.orders.OrderService,orders,com.demo.notify.NotificationGateway,notify,1,0,0,direct
 * com.demo.orders.LabelRenderer,orders,com.demo.billing.InvoiceFormatter,billing,1,1,1,cha
 * com.demo.orders.SignService,orders,com.demo.sign.AcmeSigner,sign,1,1,4,cha
 * </pre>
 *
 * <p>{@code edgeKind} is {@code cha} when every underlying method call was
 * inferred by class hierarchy analysis — see {@code docs/CHA.md}. Filtering those
 * rows out leaves the couplings backed by a call site in the source.
 *
 * <p>{@code implFanOut} says how much that inference is worth: it is the number of
 * implementations CHA was choosing between, so {@code 1} is an interface with a
 * single implementation — resolved exactly, not guessed — and only {@code > 1} is a
 * real over-approximation. Dropping every {@code cha} row therefore discards mostly
 * sound edges; {@code edgeKind == cha && implFanOut > 1} is the set actually worth
 * checking against the source.
 */
public class CsvReporter {

    private static final String HEADER =
        "source,sourceProject,target,targetProject,weight,chaWeight,implFanOut,edgeKind";

    public void write(Writer writer, ClassGraphModel model) {
        PrintWriter w = new PrintWriter(writer);

        // Project attribution lives on the nodes; edges carry only the FQNs.
        Map<String, String> projectByFqn = new HashMap<>();
        for (ClassGraphModel.Node n : model.nodes()) {
            projectByFqn.put(n.id(), n.project());
        }

        w.println(HEADER);
        for (ClassGraphModel.Edge e : model.edges()) {
            w.printf("%s,%s,%s,%s,%d,%d,%d,%s%n",
                esc(e.source()),
                esc(projectByFqn.getOrDefault(e.source(), "")),
                esc(e.target()),
                esc(projectByFqn.getOrDefault(e.target(), "")),
                e.weight(),
                e.chaWeight(),
                e.implFanOut(),
                e.kind());
        }
        w.flush();
    }

    /** RFC 4180 quoting: only fields that need it are quoted, embedded quotes are doubled. */
    private static String esc(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.indexOf(',')  >= 0
                            || value.indexOf('"')  >= 0
                            || value.indexOf('\n') >= 0
                            || value.indexOf('\r') >= 0;
        if (!needsQuoting) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
