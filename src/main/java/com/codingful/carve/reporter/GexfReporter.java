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
import java.util.List;

/**
 * Exports the class-level graph as GEXF 1.2 for Gephi.
 *
 * <p>GEXF is Gephi's native format and carries {@code viz:} attributes (colour,
 * size) so the graph is immediately useful on import — no manual styling needed.
 * Node colour follows the same role semantics as the DOT output; node size
 * scales with the number of methods collapsed into the class.
 *
 * <p>Every aggregated attribute (project, transactional, external, externalCalls,
 * cyclic, inRisk, inLockRisk, methods) is also written as a Gephi attribute column,
 * so the user can partition, filter, and re-colour by any of them inside Gephi.
 */
public class GexfReporter {

    public void write(Writer writer, ClassGraphModel model) {
        PrintWriter w = new PrintWriter(writer);

        w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        w.println("<gexf xmlns=\"http://gexf.net/1.2\" version=\"1.2\" "
            + "xmlns:viz=\"http://gexf.net/1.2/viz\">");
        w.println("  <meta><creator>carve</creator>"
            + "<description>Class-level call graph</description></meta>");
        w.println("  <graph defaultedgetype=\"directed\">");

        w.println("    <attributes class=\"node\">");
        w.println("      <attribute id=\"0\" title=\"project\" type=\"string\"/>");
        w.println("      <attribute id=\"1\" title=\"transactional\" type=\"boolean\"/>");
        w.println("      <attribute id=\"2\" title=\"external\" type=\"boolean\"/>");
        w.println("      <attribute id=\"3\" title=\"externalCalls\" type=\"string\"/>");
        w.println("      <attribute id=\"4\" title=\"cyclic\" type=\"boolean\"/>");
        w.println("      <attribute id=\"5\" title=\"inRisk\" type=\"boolean\"/>");
        w.println("      <attribute id=\"6\" title=\"inLockRisk\" type=\"boolean\"/>");
        w.println("      <attribute id=\"7\" title=\"methods\" type=\"integer\"/>");
        w.println("    </attributes>");

        w.println("    <nodes>");
        for (ClassGraphModel.Node n : model.nodes()) {
            int[] rgb = colorFor(n);
            int size = 10 + Math.min(n.methods(), 40);
            w.printf("      <node id=\"%s\" label=\"%s\">%n",
                esc(n.id()), esc(n.label()));
            w.println("        <attvalues>");
            w.printf("          <attvalue for=\"0\" value=\"%s\"/>%n", esc(n.project()));
            w.printf("          <attvalue for=\"1\" value=\"%b\"/>%n", n.transactional());
            w.printf("          <attvalue for=\"2\" value=\"%b\"/>%n", n.external());
            w.printf("          <attvalue for=\"3\" value=\"%s\"/>%n",
                esc(String.join(",", n.externalCalls())));
            w.printf("          <attvalue for=\"4\" value=\"%b\"/>%n", n.cyclic());
            w.printf("          <attvalue for=\"5\" value=\"%b\"/>%n", n.inRisk());
            w.printf("          <attvalue for=\"6\" value=\"%b\"/>%n", n.inLockRisk());
            w.printf("          <attvalue for=\"7\" value=\"%d\"/>%n", n.methods());
            w.println("        </attvalues>");
            w.printf("        <viz:color r=\"%d\" g=\"%d\" b=\"%d\"/>%n", rgb[0], rgb[1], rgb[2]);
            w.printf("        <viz:size value=\"%d\"/>%n", size);
            w.println("      </node>");
        }
        w.println("    </nodes>");

        w.println("    <edges>");
        List<ClassGraphModel.Edge> edges = model.edges();
        for (int i = 0; i < edges.size(); i++) {
            ClassGraphModel.Edge e = edges.get(i);
            w.printf("      <edge id=\"%d\" source=\"%s\" target=\"%s\" weight=\"%d\"/>%n",
                i, esc(e.source()), esc(e.target()), e.weight());
        }
        w.println("    </edges>");

        w.println("  </graph>");
        w.println("</gexf>");
        w.flush();
    }

    /** Role-based colour, matching the DOT semantics. */
    private static int[] colorFor(ClassGraphModel.Node n) {
        if (n.transactional() && n.external()) return new int[]{255, 165, 0};   // orange
        if (n.transactional())                 return new int[]{240, 220, 80};  // yellow
        if (n.external())                       return new int[]{240, 100, 100}; // coral
        return new int[]{150, 180, 220};                                        // light blue
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
