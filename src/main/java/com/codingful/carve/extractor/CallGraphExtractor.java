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

package com.codingful.carve.extractor;

import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import com.codingful.carve.model.TransactionPropagation;
import com.codingful.carve.spring.SpringMarkers;
import com.codingful.carve.util.Fqns;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Spoon scanner that traverses all source types and builds a {@link CallGraph}.
 *
 * <h3>Two-phase construction</h3>
 * <ol>
 *   <li>Scan all types with {@link #scan} — populates nodes and edges using the
 *       declared (static) type of each call target, and collects the
 *       interface-to-implementors map.</li>
 *   <li>Call {@link #resolveInterfaceCalls()} once after scanning all types —
 *       performs Class Hierarchy Analysis (CHA): for every edge that points to
 *       an interface method, additional edges are added to all known concrete
 *       implementations of that interface.</li>
 * </ol>
 *
 * <p>Without CHA, a {@code @Transactional} BO that calls a DAO <em>interface</em>
 * whose implementation uses {@code SqlSession} would produce no transaction risk,
 * because the analyser would stop at the interface boundary.
 */
public class CallGraphExtractor extends CtScanner {

    private static final Logger log = LoggerFactory.getLogger(CallGraphExtractor.class);

    private final CallGraph callGraph;
    private final UserDefinedMarkers customMarkers;
    private final ProjectResolver projectResolver;

    /** Node cache: full node id → node. Avoids duplicate objects for the same FQN. */
    private final Map<String, MethodNode> nodeCache = new HashMap<>();

    /**
     * CHA data: maps interface FQN → concrete classes that directly implement it.
     * Populated in {@link #visitCtClass}.
     */
    private final Map<String, Set<CtClass<?>>> implementors = new HashMap<>();

    /** The method currently being visited. Null when not inside a method body. */
    private MethodNode currentMethod;

    private boolean currentClassTransactional;
    private TransactionPropagation currentClassPropagation;
    private boolean currentClassReadOnly;
    private SpringComponentType currentClassComponentType;

    public CallGraphExtractor(CallGraph callGraph) {
        this(callGraph, UserDefinedMarkers.EMPTY, ProjectResolver.NONE);
    }

    public CallGraphExtractor(CallGraph callGraph, UserDefinedMarkers customMarkers) {
        this(callGraph, customMarkers, ProjectResolver.NONE);
    }

    public CallGraphExtractor(CallGraph callGraph,
                              UserDefinedMarkers customMarkers,
                              ProjectResolver projectResolver) {
        this.callGraph       = Objects.requireNonNull(callGraph);
        this.customMarkers   = customMarkers != null ? customMarkers : UserDefinedMarkers.EMPTY;
        this.projectResolver = projectResolver != null ? projectResolver : ProjectResolver.NONE;
    }

    // -----------------------------------------------------------------------
    // Type entry / exit
    // -----------------------------------------------------------------------

    @Override
    public <T> void visitCtClass(CtClass<T> ctClass) {
        registerImplementor(ctClass);
        enterType(ctClass);
        super.visitCtClass(ctClass);
        exitType();
    }

    @Override
    public <T> void visitCtInterface(CtInterface<T> ctInterface) {
        enterType(ctInterface);
        super.visitCtInterface(ctInterface);
        exitType();
    }

    @Override
    public <T extends Enum<?>> void visitCtEnum(CtEnum<T> ctEnum) {
        enterType(ctEnum);
        super.visitCtEnum(ctEnum);
        exitType();
    }

    @Override
    public void visitCtRecord(CtRecord record) {
        enterType(record);
        super.visitCtRecord(record);
        exitType();
    }

    @Override
    public <A extends java.lang.annotation.Annotation> void visitCtAnnotationType(CtAnnotationType<A> annotationType) {
        enterType(annotationType);
        super.visitCtAnnotationType(annotationType);
        exitType();
    }

    private void registerImplementor(CtClass<?> ctClass) {
        for (CtTypeReference<?> iface : ctClass.getSuperInterfaces()) {
            implementors.computeIfAbsent(iface.getQualifiedName(), k -> new HashSet<>())
                        .add(ctClass);
        }
    }

    private void enterType(CtType<?> type) {
        // Attribute the type itself, up front and independent of its methods, so a
        // method-less DTO (e.g. a pure Lombok @Data class) is still known and
        // correctly attributed to its source root.
        callGraph.registerType(type.getQualifiedName(), type.getSimpleName(),
            projectResolver.resolve(type));

        currentClassComponentType = detectComponentType(type);
        TransactionInfo txInfo = extractTransactionInfo(type.getAnnotations());
        currentClassTransactional = txInfo.present();
        currentClassPropagation   = txInfo.propagation();
        currentClassReadOnly      = txInfo.readOnly();
    }

    private void exitType() {
        currentClassComponentType = SpringComponentType.NONE;
        currentClassTransactional = false;
        currentClassPropagation   = TransactionPropagation.REQUIRED;
        currentClassReadOnly      = false;
    }

    // -----------------------------------------------------------------------
    // Method entry / exit
    // -----------------------------------------------------------------------

    @Override
    public <T> void visitCtMethod(CtMethod<T> method) {
        MethodNode node = buildApplicationNode(method);
        callGraph.addVertex(node);
        currentMethod = node;
        super.visitCtMethod(method);
        currentMethod = null;
    }

    // -----------------------------------------------------------------------
    // Invocation — core edge extraction
    // -----------------------------------------------------------------------

    @Override
    public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        if (currentMethod != null) {
            MethodNode target = buildTargetNode(invocation);
            if (target != null) {
                callGraph.addVertex(target);
                callGraph.addEdge(currentMethod, target);

                for (ExternalCallType type : target.getExternalCalls()) {
                    currentMethod.addExternalCall(type);
                }
            }
        }
        super.visitCtInvocation(invocation);
    }

    // -----------------------------------------------------------------------
    // CHA post-processing
    // -----------------------------------------------------------------------

    /**
     * Class Hierarchy Analysis: for every edge whose target is declared on an
     * interface, adds edges from the same caller to all concrete implementations
     * of that interface found in the scanned source.
     *
     * <p>Must be called <em>once</em> after all types have been scanned.
     *
     * <p>A single pass is sufficient for the common single-level case
     * (BO → DAO interface → DAO impl). Deep chains (interface calling another
     * interface) are handled by the BFS in the analyser, which follows edges
     * added by CHA through already-scanned nodes.
     */
    public void resolveInterfaceCalls() {
        // Build a secondary index keyed by "declaringTypeFqn#methodName" for fast lookup.
        Map<String, List<MethodNode>> byTypeAndMethod = new HashMap<>();
        for (MethodNode node : callGraph.vertices()) {
            String key = node.getDeclaringTypeFqn() + "#" + node.getMethodName();
            byTypeAndMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }

        // Snapshot edges to avoid ConcurrentModificationException while we add new ones.
        int resolved = 0;
        for (DefaultEdge edge : new ArrayList<>(callGraph.edges())) {
            MethodNode caller = callGraph.getRaw().getEdgeSource(edge);
            MethodNode target = callGraph.getRaw().getEdgeTarget(edge);

            Set<CtClass<?>> impls = implementors.get(target.getDeclaringTypeFqn());
            if (impls == null) continue;

            for (CtClass<?> impl : impls) {
                String key = impl.getQualifiedName() + "#" + target.getMethodName();
                List<MethodNode> concreteNodes = byTypeAndMethod.get(key);
                if (concreteNodes == null) continue;

                for (MethodNode concrete : concreteNodes) {
                    callGraph.addEdge(caller, concrete);
                    resolved++;
                    log.debug("CHA: {} → {} (via {})",
                        caller.getId(), concrete.getId(), target.getDeclaringTypeFqn());
                }
            }
        }
        log.info("CHA resolved {} interface-to-implementation edges", resolved);
        implementors.clear();
    }

    // -----------------------------------------------------------------------
    // Node construction helpers
    // -----------------------------------------------------------------------

    private MethodNode buildApplicationNode(CtMethod<?> method) {
        CtType<?> declaringType = method.getDeclaringType();
        String fqn   = declaringType != null ? declaringType.getQualifiedName() : "unknown";
        String pkg   = Fqns.packageOf(fqn);
        String cls   = declaringType != null ? declaringType.getSimpleName() : "Unknown";
        String mName = method.getSimpleName();
        String sig   = buildSignature(method);
        String id    = fqn + "#" + sig;

        MethodNode node = nodeCache.computeIfAbsent(id, k ->
            new MethodNode(id, pkg, cls, mName, fqn, true));

        if (declaringType != null) node.setProjectName(projectResolver.resolve(declaringType));
        node.setComponentType(currentClassComponentType);

        TransactionInfo txInfo = extractTransactionInfo(method.getAnnotations());
        if (txInfo.present()) {
            node.setTransactional(true);
            node.setPropagation(txInfo.propagation());
            node.setReadOnly(txInfo.readOnly());
        } else if (currentClassTransactional) {
            node.setTransactional(true);
            node.setPropagation(currentClassPropagation);
            node.setReadOnly(currentClassReadOnly);
        }

        return node;
    }

    private MethodNode buildTargetNode(CtInvocation<?> invocation) {
        CtExecutableReference<?> exec = invocation.getExecutable();
        if (exec == null) return null;

        CtTypeReference<?> declaringRef = exec.getDeclaringType();
        String fqn   = declaringRef != null ? declaringRef.getQualifiedName() : "unknown";
        String pkg   = Fqns.packageOf(fqn);
        String cls   = declaringRef != null ? declaringRef.getSimpleName() : "Unknown";
        String mName = exec.getSimpleName();
        // Use the same signature format as buildApplicationNode so that call-site stubs
        // and application nodes share the same cache key and object.
        String id    = fqn + "#" + buildTargetSignature(exec);

        return nodeCache.computeIfAbsent(id, k -> {
            boolean appCode = isApplicationType(declaringRef);
            MethodNode node = new MethodNode(id, pkg, cls, mName, fqn, appCode);

            ExternalCallType extType = SpringMarkers.detectExternalCallType(fqn);
            if (extType == null) extType = customMarkers.detect(fqn);
            if (extType != null) node.addExternalCall(extType);

            if (SpringMarkers.isSpringDataRepository(fqn)) {
                node.addExternalCall(ExternalCallType.JPA);
                node.setComponentType(SpringComponentType.REPOSITORY);
            }

            return node;
        });
    }

    // -----------------------------------------------------------------------
    // Annotation helpers
    // -----------------------------------------------------------------------

    private static SpringComponentType detectComponentType(CtType<?> type) {
        for (CtAnnotation<? extends Annotation> ann : type.getAnnotations()) {
            String fqn = ann.getAnnotationType().getQualifiedName();
            SpringComponentType ctype = SpringMarkers.componentTypeFor(fqn);
            if (ctype != null) return ctype;
        }
        return SpringComponentType.NONE;
    }

    private static TransactionInfo extractTransactionInfo(
            Collection<CtAnnotation<? extends Annotation>> annotations) {

        for (CtAnnotation<? extends Annotation> ann : annotations) {
            if (!SpringMarkers.isTransactionalAnnotation(
                    ann.getAnnotationType().getQualifiedName())) continue;

            TransactionPropagation propagation = TransactionPropagation.REQUIRED;
            boolean readOnly = false;

            try {
                var values = ann.getValues();

                Object propVal = values.get("propagation");
                if (propVal != null) {
                    String propStr = propVal.toString().trim();
                    int dot = propStr.lastIndexOf('.');
                    propagation = SpringMarkers.parsePropagation(
                        dot >= 0 ? propStr.substring(dot + 1) : propStr);
                }

                Object roVal = values.get("readOnly");
                if (roVal != null && "true".equals(roVal.toString().trim())) {
                    readOnly = true;
                }
            } catch (Exception e) {
                log.trace("Could not read @Transactional attributes: {}", e.getMessage());
            }

            return new TransactionInfo(true, propagation, readOnly);
        }

        return TransactionInfo.ABSENT;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String buildSignature(CtMethod<?> method) {
        List<String> params = method.getParameters().stream()
            .map(p -> p.getType().getSimpleName())
            .toList();
        return buildSig(method.getSimpleName(), params);
    }

    /**
     * Builds a method signature string from a call-site executable reference,
     * using the same simple-name format as {@link #buildSignature(CtMethod)} so
     * that the resulting node-cache key matches the application-node key when the
     * target is an in-source type.
     */
    private static String buildTargetSignature(CtExecutableReference<?> exec) {
        List<String> params = exec.getParameters().stream()
            .map(CtTypeReference::getSimpleName)
            .toList();
        return buildSig(exec.getSimpleName(), params);
    }

    private static String buildSig(String name, List<String> params) {
        return params.isEmpty() ? name + "()" : name + "(" + String.join(",", params) + ")";
    }

    private static boolean isApplicationType(CtTypeReference<?> ref) {
        if (ref == null) return false;
        try {
            CtType<?> decl = ref.getTypeDeclaration();
            return decl != null && decl.getPosition().isValidPosition();
        } catch (Exception e) {
            return false;
        }
    }

    private record TransactionInfo(boolean present,
                                   TransactionPropagation propagation,
                                   boolean readOnly) {
        static final TransactionInfo ABSENT =
            new TransactionInfo(false, TransactionPropagation.REQUIRED, false);
    }
}
