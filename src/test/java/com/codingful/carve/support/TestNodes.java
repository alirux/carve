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

package com.codingful.carve.support;

import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;

/**
 * Fluent builder for real {@link MethodNode} instances used across the unit
 * tests. This is a test data builder, not a mock: it returns fully-constructed,
 * behaviourally-real domain objects so tests can exercise production code with
 * genuine collaborators (classical / Detroit style).
 */
public final class TestNodes {

    private TestNodes() { }

    /** Starts a builder for an application-code node with the given id. */
    public static Builder app(String id) { return new Builder(id, true); }

    /** Starts a builder for a library-code node (not part of the analysed source). */
    public static Builder lib(String id) { return new Builder(id, false); }

    public static final class Builder {
        private final String id;
        private final boolean applicationCode;
        private String packageName = "app";
        private String className   = "C";
        private String methodName  = "m";
        private SpringComponentType componentType = SpringComponentType.NONE;
        private boolean transactional;
        private String projectName = "";
        private ExternalCallType[] calls = new ExternalCallType[0];

        private Builder(String id, boolean applicationCode) {
            this.id = id;
            this.applicationCode = applicationCode;
        }

        public Builder pkg(String packageName)        { this.packageName = packageName; return this; }
        public Builder className(String className)     { this.className = className; return this; }
        public Builder method(String methodName)       { this.methodName = methodName; return this; }
        public Builder type(SpringComponentType type)  { this.componentType = type; return this; }
        public Builder transactional()                 { this.transactional = true; return this; }
        public Builder project(String projectName)     { this.projectName = projectName; return this; }
        public Builder calls(ExternalCallType... c)    { this.calls = c; return this; }

        public MethodNode build() {
            MethodNode node = new MethodNode(
                id, packageName, className, methodName,
                packageName + "." + className, applicationCode);
            node.setComponentType(componentType);
            node.setTransactional(transactional);
            node.setProjectName(projectName);
            for (ExternalCallType c : calls) node.addExternalCall(c);
            return node;
        }
    }
}
