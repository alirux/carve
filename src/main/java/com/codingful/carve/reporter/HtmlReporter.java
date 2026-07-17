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

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a self-contained, interactive 3D viewer for the class-level graph.
 *
 * <p>The page embeds the graph data as JSON and renders it with
 * <a href="https://github.com/vasturiano/3d-force-graph">3d-force-graph</a>
 * (WebGL / Three.js) loaded from a CDN. It supports rotate / zoom / pan,
 * per-project filtering, a "risks only" filter, risk highlighting, and search.
 *
 * <p>The HTML/CSS/JS template lives in
 * {@code src/main/resources/com/codingful/carve/reporter/class-graph.html}.
 * The single placeholder {@code {{DATA}}} is replaced with the serialised graph JSON.
 */
public class HtmlReporter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TEMPLATE = loadTemplate("class-graph.html");

    public void write(Writer writer, ClassGraphModel model) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", model.nodes());
        data.put("links", model.edges());
        data.put("multiProject", model.multiProject());
        data.put("meta", ReportMetadata.asMap());
        String json = MAPPER.writeValueAsString(data);

        writer.write(TEMPLATE.replace("{{DATA}}", json)
            .replace("{{META}}", ReportMetadata.asXmlComment()));
        writer.flush();
    }

    private static String loadTemplate(String name) {
        try (InputStream is = HtmlReporter.class.getResourceAsStream(name)) {
            if (is == null) throw new ExceptionInInitializerError("Missing classpath resource: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
