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
import spoon.Launcher;
import spoon.reflect.CtModel;

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
}
