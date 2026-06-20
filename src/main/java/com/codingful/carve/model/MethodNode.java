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

package com.codingful.carve.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A vertex in the call graph representing a single method (or constructor).
 *
 * Identity is based on {@link #id}, which is the fully qualified class name
 * plus the method signature: {@code com.example.Foo#doSomething(java.lang.String)}.
 */
public final class MethodNode {

    private final String id;
    private final String packageName;
    private final String className;
    private final String methodName;
    private final String declaringTypeFqn;

    /** True when the method belongs to the source being analysed, false for library code. */
    private final boolean applicationCode;

    /** Named project this method belongs to; empty string in single-project mode. */
    private String projectName = "";

    private SpringComponentType componentType = SpringComponentType.NONE;

    private boolean transactional;
    private TransactionPropagation propagation = TransactionPropagation.REQUIRED;
    private boolean readOnly;

    private final EnumSet<ExternalCallType> externalCalls = EnumSet.noneOf(ExternalCallType.class);

    public MethodNode(String id,
                      String packageName,
                      String className,
                      String methodName,
                      String declaringTypeFqn,
                      boolean applicationCode) {
        this.id = Objects.requireNonNull(id);
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.declaringTypeFqn = declaringTypeFqn;
        this.applicationCode = applicationCode;
    }

    // -----------------------------------------------------------------------
    // Mutators used during graph construction
    // -----------------------------------------------------------------------

    public void setProjectName(String projectName) {
        this.projectName = projectName != null ? projectName : "";
    }

    public void setComponentType(SpringComponentType componentType) {
        this.componentType = componentType;
    }

    public void setTransactional(boolean transactional) {
        this.transactional = transactional;
    }

    public void setPropagation(TransactionPropagation propagation) {
        this.propagation = propagation;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public void addExternalCall(ExternalCallType type) {
        externalCalls.add(type);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getId()              { return id; }
    public String getProjectName()     { return projectName; }
    public String getPackageName()     { return packageName; }
    public String getClassName()       { return className; }
    public String getMethodName()      { return methodName; }
    public String getDeclaringTypeFqn(){ return declaringTypeFqn; }
    public boolean isApplicationCode() { return applicationCode; }
    public SpringComponentType getComponentType() { return componentType; }
    public boolean isTransactional()   { return transactional; }
    public TransactionPropagation getPropagation() { return propagation; }
    public boolean isReadOnly()        { return readOnly; }
    public Set<ExternalCallType> getExternalCalls() {
        return Collections.unmodifiableSet(externalCalls);
    }

    public boolean makesExternalCall() { return !externalCalls.isEmpty(); }

    public boolean makesHttpCall()      { return externalCalls.contains(ExternalCallType.HTTP); }
    public boolean makesDbCall()        {
        return externalCalls.contains(ExternalCallType.JDBC)
            || externalCalls.contains(ExternalCallType.JPA);
    }
    public boolean makesMessagingCall() { return externalCalls.contains(ExternalCallType.MESSAGING); }

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodNode other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return id; }
}
