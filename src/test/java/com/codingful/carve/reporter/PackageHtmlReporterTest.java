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

import com.codingful.carve.graph.CallGraph;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

class PackageHtmlReporterTest {

    private static final String PLACEHOLDER = "{{DATA}}";
    private static final String DOCTYPE     = "<!DOCTYPE html>";
    private static final String K_NODES     = "\"nodes\"";
    private static final String K_LINKS     = "\"links\"";
    private static final String K_MULTI     = "\"multiProject\"";

    private static com.codingful.carve.support.TestNodes.Builder method(String pkg, String cls, String id) {
        return app(pkg + "." + cls + "#" + id).pkg(pkg).className(cls).method(id);
    }

    private static PackageGraphModel model(CallGraph cg) {
        return PackageGraphModel.collapse(
            ClassGraphModel.collapse(cg, List.of(), List.of(), List.of(), List.of()));
    }

    private static String write(PackageGraphModel model) throws IOException {
        StringWriter w = new StringWriter();
        new PackageHtmlReporter().write(w, model);
        return w.toString();
    }

    @Test
    void GIVEN_a_package_model_WHEN_writing_html_THEN_template_is_kept_and_placeholder_is_replaced_with_data()
            throws IOException {
        CallGraph cg = new CallGraph();
        cg.addEdge(method("app.web", "A", "m").build(), method("app.svc", "B", "n").build());

        String html = write(model(cg));

        assertThat(html).contains(DOCTYPE);          // template preserved
        assertThat(html).doesNotContain(PLACEHOLDER); // placeholder substituted
        assertThat(html).contains(K_NODES, K_LINKS, K_MULTI);
        assertThat(html).contains("app.web");         // a package node id from the model
        assertThat(html).contains("\"multiProject\":false");
    }

    @Test
    void GIVEN_a_multi_project_model_WHEN_writing_html_THEN_the_multi_project_flag_is_embedded()
            throws IOException {
        CallGraph cg = new CallGraph();
        cg.addVertex(method("api.web", "A", "m").project("api").build());
        cg.addVertex(method("core.svc", "B", "m").project("core").build());

        assertThat(write(model(cg))).contains("\"multiProject\":true");
    }
}
