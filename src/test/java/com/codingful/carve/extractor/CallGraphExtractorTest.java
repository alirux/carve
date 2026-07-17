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
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import com.codingful.carve.model.TransactionPropagation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphExtractorTest {

    /**
     * Parses the given source snippet (as a virtual file named after the class)
     * and returns a populated CallGraph.
     */
    private static CallGraph parse(String className, String source) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(
            new spoon.support.compiler.VirtualFile(source, className + ".java")
        );
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);

        CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(cg);
        model.getAllTypes().forEach(extractor::scan);
        return cg;
    }

    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_service_annotated_class_WHEN_extracting_THEN_the_stereotype_is_detected() {
        String source = """
            package com.example;

            import org.springframework.stereotype.Service;

            @Service
            public class OrderService {
                public void placeOrder() {}
            }
            """;

        CallGraph cg = parse("OrderService", source);

        Optional<MethodNode> node = cg.applicationNodes().stream()
            .filter(n -> n.getMethodName().equals("placeOrder"))
            .findFirst();

        assertThat(node).isPresent();
        assertThat(node.get().getComponentType()).isEqualTo(SpringComponentType.SERVICE);
    }

    @Test
    void GIVEN_a_class_level_transactional_WHEN_extracting_THEN_its_methods_are_transactional() {
        String source = """
            package com.example;

            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.stereotype.Service;

            @Service
            @Transactional
            public class PaymentService {
                public void pay() {}
                public void refund() {}
            }
            """;

        CallGraph cg = parse("PaymentService", source);

        long txCount = cg.applicationNodes().stream()
            .filter(MethodNode::isTransactional)
            .count();

        assertThat(txCount).isEqualTo(2); // pay() and refund() both inherit class-level tx
    }

    @Test
    void GIVEN_a_method_level_transactional_WHEN_extracting_THEN_it_overrides_the_class_level() {
        String source = """
            package com.example;

            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.transaction.annotation.Propagation;

            @Transactional
            public class MyService {
                public void defaultTx() {}

                @Transactional(propagation = Propagation.REQUIRES_NEW)
                public void requiresNew() {}
            }
            """;

        CallGraph cg = parse("MyService", source);

        Optional<MethodNode> reqNew = cg.applicationNodes().stream()
            .filter(n -> n.getMethodName().equals("requiresNew"))
            .findFirst();

        assertThat(reqNew).isPresent();
        assertThat(reqNew.get().getPropagation()).isEqualTo(TransactionPropagation.REQUIRES_NEW);
    }

    @Test
    void GIVEN_a_method_call_WHEN_extracting_THEN_a_call_edge_is_created() {
        String source = """
            package com.example;

            public class A {
                private final B b = new B();
                public void callB() { b.doWork(); }
            }

            class B {
                public void doWork() {}
            }
            """;

        CallGraph cg = parse("A", source);

        boolean edgeExists = cg.edges().stream().anyMatch(e -> {
            MethodNode src = cg.getRaw().getEdgeSource(e);
            MethodNode tgt = cg.getRaw().getEdgeTarget(e);
            return src.getMethodName().equals("callB")
                && tgt.getMethodName().equals("doWork");
        });

        assertThat(edgeExists).isTrue();
    }

    @Test
    void GIVEN_a_rest_template_call_WHEN_extracting_THEN_it_is_an_http_external_call() {
        String source = """
            package com.example;

            import org.springframework.stereotype.Service;
            import org.springframework.web.client.RestTemplate;

            @Service
            public class ExternalService {
                private final RestTemplate restTemplate = new RestTemplate();

                public String fetch(String url) {
                    return restTemplate.getForObject(url, String.class);
                }
            }
            """;

        CallGraph cg = parse("ExternalService", source);

        Optional<MethodNode> fetchNode = cg.applicationNodes().stream()
            .filter(n -> n.getMethodName().equals("fetch"))
            .findFirst();

        assertThat(fetchNode).isPresent();
        assertThat(fetchNode.get().makesHttpCall()).isTrue();
    }

    @Test
    void GIVEN_a_sql_session_call_WHEN_extracting_THEN_it_is_a_jdbc_external_call() {
        // Common MyBatis pattern:
        // @Component DAO impl that @Autowires SqlSession (backed by SqlSessionTemplate at runtime)
        // and calls selectOne / insert / update / delete with string statement IDs.
        String source = """
            package com.example.dao.mybatis;

            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;
            import org.apache.ibatis.session.SqlSession;

            @Component("tokenDAO")
            public class TokenDAOMyIbatisImpl {

                @Autowired
                private SqlSession sqlSession;

                public Object findToken(String token) {
                    return sqlSession.selectOne("findToken", token);
                }

                public void insertToken(Object tk) {
                    sqlSession.insert("insertToken", tk);
                }
            }
            """;

        CallGraph cg = parse("TokenDAOMyIbatisImpl", source);

        cg.applicationNodes().stream()
            .filter(n -> n.getMethodName().equals("findToken")
                      || n.getMethodName().equals("insertToken"))
            .forEach(n ->
                assertThat(n.makesDbCall())
                    .as("method %s should be tagged as a DB call", n.getMethodName())
                    .isTrue()
            );
    }

    @Test
    void GIVEN_an_http_call_hidden_behind_an_interface_WHEN_resolving_with_cha_THEN_the_risk_is_found() {
        // A @Transactional BO calls a NotificationService interface whose
        // concrete implementation uses RestTemplate. Without CHA the analyser
        // stops at the interface and misses the HTTP-inside-transaction risk.
        String source = """
            package com.example;

            import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.web.client.RestTemplate;
            import org.springframework.beans.factory.annotation.Autowired;

            interface NotificationService {
                void notify(String payload);
            }

            @Component
            class RestNotificationService implements NotificationService {
                @Autowired private RestTemplate restTemplate;

                public void notify(String payload) {
                    restTemplate.postForObject("http://notify.internal/api", payload, String.class);
                }
            }

            @Component
            @Transactional
            class OrderBO {
                @Autowired private NotificationService notificationService;

                public void placeOrder(Object order) {
                    // … persist order (omitted) …
                    notificationService.notify("order placed");  // HTTP inside open tx
                }
            }
            """;

        spoon.Launcher launcher = new spoon.Launcher();
        launcher.addInputResource(new spoon.support.compiler.VirtualFile(source, "Order.java"));
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        spoon.reflect.CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(cg);
        model.getAllTypes().forEach(extractor::scan);

        // Before CHA: the interface boundary blocks traversal → no risk
        var risksBeforeCha = new com.codingful.carve.analyzer.TransactionAnalyzer(cg)
            .findRisks();

        extractor.resolveInterfaceCalls();

        // After CHA: OrderBO → RestNotificationService → RestTemplate is visible → risk found
        var risksAfterCha = new com.codingful.carve.analyzer.TransactionAnalyzer(cg)
            .findRisks();

        assertThat(risksBeforeCha).isEmpty();
        assertThat(risksAfterCha).isNotEmpty();
        assertThat(risksAfterCha.get(0).callTypes())
            .contains(com.codingful.carve.model.ExternalCallType.HTTP);
    }

    @Test
    void GIVEN_calls_to_a_lombok_getter_and_a_hand_written_method_WHEN_extracting_THEN_both_targets_resolve_to_the_dto(@TempDir Path dir) throws IOException {
        // Same DTO "Money" under project "core", exposing BOTH:
        //   - label()     : hand-written, so it exists as a declared method
        //   - getAmount() : generated by @Data, so Spoon (no Lombok) never sees it
        // A controller under project "api" calls both on the same receiver.
        //
        // The Lombok getter cannot be bound to a declaring type. resolveDeclaringType
        // recovers the owning type from the static type of the receiver, so BOTH
        // calls point at com.acme.core.Money and the coupling is preserved. Project
        // attribution of the DTO itself comes from the type registry, not from the
        // call-site stub.
        Path tmp = dir.toRealPath();
        Path core = writePackagedClass(tmp.resolve("core"), "com/acme/core", "Money",
            """
            package com.acme.core;
            import lombok.Data;
            @Data
            public class Money {
                private java.math.BigDecimal amount;
                public String label() { return "money"; } // hand-written
            }
            """);
        Path api = writePackagedClass(tmp.resolve("api"), "com/acme/api", "PriceController",
            """
            package com.acme.api;
            import com.acme.core.Money;
            public class PriceController {
                public void show(Money m) {
                    m.label();       // resolves to a declared method
                    m.getAmount();   // Lombok-generated, absent from the model
                }
            }
            """);

        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("api", api.toString());
        roots.put("core", core.toString());
        ProjectResolver resolver = ProjectResolver.of(roots);

        Launcher launcher = new Launcher();
        launcher.addInputResource(api.toString());
        launcher.addInputResource(core.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor =
            new CallGraphExtractor(cg, UserDefinedMarkers.EMPTY, resolver);
        model.getAllTypes().forEach(extractor::scan);

        MethodNode show = nodeByMethod(cg, "show");
        MethodNode label = nodeByMethod(cg, "label");
        MethodNode getAmount = nodeByMethod(cg, "getAmount");

        // The hand-written method: resolved to the real type and attributed to core.
        assertThat(label.getDeclaringTypeFqn()).isEqualTo("com.acme.core.Money");
        assertThat(label.isApplicationCode()).isTrue();
        assertThat(label.getProjectName()).isEqualTo("core");

        // The Lombok getter: recovered from the receiver's static type, so the
        // target still points at the DTO and counts as application code — the
        // coupling edge is kept rather than dropped onto "unknown".
        assertThat(getAmount.getDeclaringTypeFqn())
            .as("Lombok getter target recovered from the receiver type")
            .isEqualTo("com.acme.core.Money");
        assertThat(getAmount.isApplicationCode()).isTrue();

        boolean edgeToDto = cg.edges().stream().anyMatch(e ->
            cg.getRaw().getEdgeSource(e).equals(show)
            && cg.getRaw().getEdgeTarget(e).equals(getAmount));
        assertThat(edgeToDto).as("api → core coupling via the Lombok getter").isTrue();

        // The DTO's project comes from the type registry (its own source file),
        // not from the call-site stub.
        assertThat(cg.types())
            .anyMatch(t -> t.fqn().equals("com.acme.core.Money") && t.project().equals("core"));
    }

    @Test
    void GIVEN_a_lombok_builder_chain_WHEN_extracting_THEN_the_dto_is_attributed_but_the_coupling_is_not_recovered(@TempDir Path dir) throws IOException {
        // Known limit of the receiver-type fallback. A @Builder chain
        //   Money.builder().amount(x).build()
        // has no bindable declarations AND intermediate receivers whose type is
        // itself unresolved (the generated MoneyBuilder). So amount()/build()
        // degrade to "unknown" and the coupling Factory → Money is lost — unlike
        // a getter on a typed receiver, which the fallback recovers.
        //
        // The DTO is still present and correctly attributed, because that comes
        // from the type registry (its own source file), not from call resolution.
        Path tmp = dir.toRealPath();
        Path core = writePackagedClass(tmp.resolve("core"), "com/acme/core", "Money",
            """
            package com.acme.core;
            import lombok.Builder;
            @Builder
            public class Money { private java.math.BigDecimal amount; }
            """);
        Path api = writePackagedClass(tmp.resolve("api"), "com/acme/api", "Factory",
            """
            package com.acme.api;
            import com.acme.core.Money;
            public class Factory {
                public Money make() { return Money.builder().amount(null).build(); }
            }
            """);

        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("api", api.toString());
        roots.put("core", core.toString());
        ProjectResolver resolver = ProjectResolver.of(roots);

        Launcher launcher = new Launcher();
        launcher.addInputResource(api.toString());
        launcher.addInputResource(core.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor =
            new CallGraphExtractor(cg, UserDefinedMarkers.EMPTY, resolver);
        model.getAllTypes().forEach(extractor::scan);

        MethodNode make = nodeByMethod(cg, "make");

        // Attribution/presence: the DTO is registered and attributed regardless.
        assertThat(cg.types())
            .anyMatch(t -> t.fqn().equals("com.acme.core.Money") && t.project().equals("core"));

        // Coupling: the builder chain never lands on Money, so make() has no
        // application-code edge into the core package.
        boolean couplingRecovered = cg.edges().stream().anyMatch(e ->
            cg.getRaw().getEdgeSource(e).equals(make)
            && cg.getRaw().getEdgeTarget(e).isApplicationCode()
            && cg.getRaw().getEdgeTarget(e).getDeclaringTypeFqn().startsWith("com.acme.core"));
        assertThat(couplingRecovered)
            .as("builder-chain coupling to the DTO is a known gap")
            .isFalse();
    }

    @Test
    void GIVEN_a_recursive_method_WHEN_extracting_THEN_the_graph_has_no_self_loops() {
        String source = """
            package com.example;

            public class Recursive {
                public int factorial(int n) {
                    if (n <= 1) return 1;
                    return n * factorial(n - 1);
                }
            }
            """;

        CallGraph cg = parse("Recursive", source);

        boolean hasSelfLoop = cg.edges().stream().anyMatch(e -> {
            MethodNode src = cg.getRaw().getEdgeSource(e);
            MethodNode tgt = cg.getRaw().getEdgeTarget(e);
            return src.equals(tgt);
        });

        assertThat(hasSelfLoop).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Writes {@code source} to {@code <root>/<packagePath>/<name>.java} and
     * returns the source {@code root} (the path to register with a source root).
     */
    private static Path writePackagedClass(Path root, String packagePath, String name, String source)
            throws IOException {
        Path pkgDir = root.resolve(packagePath);
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve(name + ".java"), source);
        return root;
    }

    private static MethodNode nodeByMethod(CallGraph cg, String methodName) {
        return cg.vertices().stream()
            .filter(n -> n.getMethodName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("node not found: " + methodName));
    }
}
