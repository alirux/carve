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

import com.codingful.carve.analyzer.LockRiskAnalyzer.NestedTxRisk;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

class GexfReporterTest {

    // GEXF element names (the format structure).
    private static final String EL_NODE      = "node";
    private static final String EL_EDGE      = "edge";
    private static final String EL_GRAPH     = "graph";
    private static final String EL_ATTRIBUTE = "attribute";
    private static final String EL_ATTVALUE  = "attvalue";
    private static final String EL_COLOR     = "viz:color";
    private static final String EL_SIZE      = "viz:size";

    // Node attribute-column titles (the schema contract), declared once.
    private static final String COL_PROJECT        = "project";
    private static final String COL_TRANSACTIONAL  = "transactional";
    private static final String COL_EXTERNAL       = "external";
    private static final String COL_EXTERNAL_CALLS = "externalCalls";
    private static final String COL_CYCLIC         = "cyclic";
    private static final String COL_IN_RISK        = "inRisk";
    private static final String COL_IN_LOCK_RISK   = "inLockRisk";
    private static final String COL_METHODS        = "methods";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static ClassGraphModel model(CallGraph cg) {
        return ClassGraphModel.collapse(cg, List.of(), List.of(), List.of(), List.of());
    }

    /** Writes the model to GEXF and parses it back into a DOM (which also validates well-formedness). */
    private static Document writeAndParse(ClassGraphModel model) throws Exception {
        StringWriter sw = new StringWriter();
        new GexfReporter().write(sw, model);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(sw.toString())));
    }

    /** Collapses the call graph to a class model, then writes and parses the GEXF. */
    private static Document writeAndParse(CallGraph cg) throws Exception {
        return writeAndParse(model(cg));
    }

    private static Element nodeById(Document doc, String id) {
        NodeList nodes = doc.getElementsByTagName(EL_NODE);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (e.getAttribute("id").equals(id)) return e;
        }
        throw new AssertionError("no node: " + id);
    }

    /** The declared id of a node attribute column, looked up by its title. */
    private static String attributeId(Document doc, String title) {
        NodeList attrs = doc.getElementsByTagName(EL_ATTRIBUTE);
        for (int i = 0; i < attrs.getLength(); i++) {
            Element a = (Element) attrs.item(i);
            if (a.getAttribute("title").equals(title)) return a.getAttribute("id");
        }
        throw new AssertionError("no attribute column: " + title);
    }

    /** A node's attvalue for the column with the given title (id derived from the schema). */
    private static String attvalue(Document doc, Element node, String title) {
        String id = attributeId(doc, title);
        NodeList vals = node.getElementsByTagName(EL_ATTVALUE);
        for (int i = 0; i < vals.getLength(); i++) {
            Element v = (Element) vals.item(i);
            if (v.getAttribute("for").equals(id)) return v.getAttribute("value");
        }
        throw new AssertionError("no attvalue for: " + title);
    }

    private static int[] color(Element node) {
        Element c = (Element) node.getElementsByTagName(EL_COLOR).item(0);
        return new int[]{
            Integer.parseInt(c.getAttribute("r")),
            Integer.parseInt(c.getAttribute("g")),
            Integer.parseInt(c.getAttribute("b"))};
    }

    private static int size(Element node) {
        Element s = (Element) node.getElementsByTagName(EL_SIZE).item(0);
        return Integer.parseInt(s.getAttribute("value"));
    }

    // -----------------------------------------------------------------------
    // Skeleton
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_model_WHEN_writing_gexf_THEN_output_is_well_formed_with_the_gexf_skeleton()
            throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        Document doc = writeAndParse(cg);

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("gexf");
        assertThat(doc.getDocumentElement().getAttribute("version")).isEqualTo("1.2");
        Element graph = (Element) doc.getElementsByTagName(EL_GRAPH).item(0);
        assertThat(graph.getAttribute("defaultedgetype")).isEqualTo("directed");

        List<String> columns = new ArrayList<>();
        NodeList attrs = doc.getElementsByTagName(EL_ATTRIBUTE);
        for (int i = 0; i < attrs.getLength(); i++) {
            columns.add(((Element) attrs.item(i)).getAttribute("title"));
        }
        assertThat(columns).containsExactly(COL_PROJECT, COL_TRANSACTIONAL, COL_EXTERNAL,
            COL_EXTERNAL_CALLS, COL_CYCLIC, COL_IN_RISK, COL_IN_LOCK_RISK, COL_METHODS);
    }

    @Test
    void GIVEN_a_model_WHEN_writing_gexf_THEN_the_copyright_note_and_generation_date_are_embedded()
            throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "A", "m").build());

        StringWriter sw = new StringWriter();
        new GexfReporter().write(sw, model(cg));
        String gexf = sw.toString();

        assertThat(gexf).contains(ReportMetadata.asXmlComment());

        Document doc = writeAndParse(cg);
        Element meta = (Element) doc.getElementsByTagName("meta").item(0);
        assertThat(meta.getAttribute("lastmodifieddate")).isEqualTo(ReportMetadata.generatedOn().toString());
    }

    // -----------------------------------------------------------------------
    // Nodes
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_class_node_WHEN_writing_gexf_THEN_id_label_and_method_count_are_written()
            throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "a").build());
        cg.addVertex(method("app", "Svc", "b").build());

        Document doc = writeAndParse(cg);

        Element node = nodeById(doc, "app.Svc");
        assertThat(node.getAttribute("label")).isEqualTo("Svc");
        assertThat(attvalue(doc, node, COL_METHODS)).isEqualTo("2");
        assertThat(attvalue(doc, node, COL_PROJECT)).isEmpty();
    }

    @Test
    void GIVEN_external_call_types_WHEN_writing_gexf_THEN_they_are_comma_joined() throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Svc", "a")
            .calls(ExternalCallType.HTTP, ExternalCallType.JDBC).build());

        Document doc = writeAndParse(cg);

        Element node = nodeById(doc, "app.Svc");
        assertThat(attvalue(doc, node, COL_EXTERNAL)).isEqualTo("true");
        assertThat(attvalue(doc, node, COL_EXTERNAL_CALLS)).isEqualTo("HTTP,JDBC"); // sorted, joined
    }

    @Test
    void GIVEN_risk_flagged_classes_WHEN_writing_gexf_THEN_risk_attributes_are_true() throws Exception {
        var a = method("app", "A", "m").build();
        var b = method("app", "B", "n").build();
        CallGraph cg = new CallGraph();
        cg.addVertex(a);
        cg.addVertex(b);
        var risk   = new TransactionRisk(a, a, Set.of(ExternalCallType.HTTP), List.of(a));
        var nested = new NestedTxRisk(a, a, List.of(a));
        ClassGraphModel cm = ClassGraphModel.collapse(
            cg, List.of(risk), List.of(Set.of(a, b)), List.of(nested), List.of());

        Document doc = writeAndParse(cm);

        Element node = nodeById(doc, "app.A");
        assertThat(attvalue(doc, node, COL_CYCLIC)).isEqualTo("true");
        assertThat(attvalue(doc, node, COL_IN_RISK)).isEqualTo("true");
        assertThat(attvalue(doc, node, COL_IN_LOCK_RISK)).isEqualTo("true");
    }

    // -----------------------------------------------------------------------
    // Visual attributes
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_class_role_variants_WHEN_writing_gexf_THEN_color_matches_the_role() throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "TxExt", "m").transactional().calls(ExternalCallType.HTTP).build());
        cg.addVertex(method("app", "Tx", "m").transactional().build());
        cg.addVertex(method("app", "Ext", "m").calls(ExternalCallType.HTTP).build());
        cg.addVertex(method("app", "Plain", "m").build());

        Document doc = writeAndParse(cg);

        assertThat(color(nodeById(doc, "app.TxExt"))).containsExactly(255, 165, 0);   // orange
        assertThat(color(nodeById(doc, "app.Tx"))).containsExactly(240, 220, 80);     // yellow
        assertThat(color(nodeById(doc, "app.Ext"))).containsExactly(240, 100, 100);   // coral
        assertThat(color(nodeById(doc, "app.Plain"))).containsExactly(150, 180, 220); // light blue
    }

    @Test
    void GIVEN_classes_of_different_sizes_WHEN_writing_gexf_THEN_node_size_scales_and_caps()
            throws Exception {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("app", "Small", "1").build());          // 1 method  → 11
        for (int i = 0; i < 45; i++) {
            cg.addVertex(method("app", "Big", String.valueOf(i)).build()); // 45 methods → capped 50
        }

        Document doc = writeAndParse(cg);

        assertThat(size(nodeById(doc, "app.Small"))).isEqualTo(11);   // 10 + min(1, 40)
        assertThat(size(nodeById(doc, "app.Big"))).isEqualTo(50);     // 10 + min(45, 40)
    }

    // -----------------------------------------------------------------------
    // Edges & escaping
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_cross_class_calls_WHEN_writing_gexf_THEN_edges_carry_source_target_and_weight()
            throws Exception {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app", "A", "1").build(), method("app", "B", "1").build());
        cg.addEdge(method("app", "A", "2").build(), method("app", "B", "1").build());

        Document doc = writeAndParse(cg);

        NodeList edges = doc.getElementsByTagName(EL_EDGE);
        assertThat(edges.getLength()).isEqualTo(1);
        Element e = (Element) edges.item(0);
        assertThat(e.getAttribute("source")).isEqualTo("app.A");
        assertThat(e.getAttribute("target")).isEqualTo("app.B");
        assertThat(e.getAttribute("weight")).isEqualTo("2");
    }

    @Test
    void GIVEN_special_characters_in_a_value_WHEN_writing_gexf_THEN_they_are_xml_escaped()
            throws Exception {
        CallGraph cg = new CallGraph();
        // A project name with XML metacharacters: if escaping were wrong the DOM
        // parse below would throw, and the round-tripped value would not match.
        cg.addVertex(method("app", "A", "m").project("x&y<z\"q").build());

        Document doc = writeAndParse(cg);

        assertThat(attvalue(doc, nodeById(doc, "app.A"), COL_PROJECT)).isEqualTo("x&y<z\"q");
    }

    @Test
    void GIVEN_a_node_with_a_null_label_WHEN_writing_gexf_THEN_it_is_emitted_as_empty() throws Exception {
        // A MethodNode may carry a null class name; the escaper must render it as "".
        MethodNode nullClass = new MethodNode("app.X#m", "app", null, "m", "app.X", true);
        CallGraph cg = new CallGraph();
        cg.addVertex(nullClass);

        Document doc = writeAndParse(cg);

        assertThat(nodeById(doc, "app.X").getAttribute("label")).isEmpty();
    }
}
