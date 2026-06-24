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
import com.codingful.carve.spring.SpringMarkers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

import java.util.stream.Stream;

import static com.codingful.carve.model.ExternalCallType.CACHE;
import static com.codingful.carve.model.ExternalCallType.HTTP;
import static com.codingful.carve.model.ExternalCallType.JDBC;
import static com.codingful.carve.model.ExternalCallType.JPA;
import static com.codingful.carve.model.ExternalCallType.MESSAGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Detection of external-call kinds from real source: for each supported client a
 * tiny class invokes it, the source is parsed by Spoon and run through the
 * extractor, and the calling method must be tagged with the expected
 * {@link ExternalCallType}.
 */
class ExternalCallDetectionTest {

    static Stream<Arguments> clients() {
        return Stream.of(
            // HTTP
            arguments(SpringMarkers.REST_TEMPLATE,        "RestTemplate",   "getForObject(\"u\", String.class)", HTTP),
            arguments(SpringMarkers.WEB_CLIENT,           "WebClient",      "get()",                    HTTP),
            arguments(SpringMarkers.REST_CLIENT,          "RestClient",     "get()",                    HTTP),
            arguments(SpringMarkers.JAVA_HTTP_CLIENT,     "HttpClient",     "version()",                HTTP),
            arguments(SpringMarkers.OKHTTP_CLIENT,        "OkHttpClient",   "connectionPool()",         HTTP),
            arguments(SpringMarkers.APACHE_HTTP_CLIENT4,  "HttpClient",     "execute(null)",            HTTP),
            arguments(SpringMarkers.APACHE_HTTP_CLIENT5,  "HttpClient",     "execute(null)",            HTTP),
            // JDBC
            arguments(SpringMarkers.JDBC_TEMPLATE,        "JdbcTemplate",   "update(\"sql\")",          JDBC),
            arguments(SpringMarkers.NAMED_JDBC_TEMPLATE,  "NamedParameterJdbcTemplate", "getJdbcTemplate()", JDBC),
            arguments(SpringMarkers.MYBATIS_SQL_SESSION,  "SqlSession",     "selectOne(\"s\")",         JDBC),
            arguments(SpringMarkers.MYBATIS_SQL_SESSION_TEMPLATE, "SqlSessionTemplate", "selectOne(\"s\")", JDBC),
            // JPA
            arguments(SpringMarkers.ENTITY_MANAGER_JAVAX,   "EntityManager", "flush()",                 JPA),
            arguments(SpringMarkers.ENTITY_MANAGER_JAKARTA, "EntityManager", "flush()",                 JPA),
            arguments("org.springframework.data.jpa.repository.JpaRepository", "JpaRepository", "findAll()", JPA),
            // MESSAGING
            arguments(SpringMarkers.KAFKA_TEMPLATE,       "KafkaTemplate",  "flush()",                  MESSAGING),
            arguments(SpringMarkers.RABBIT_TEMPLATE,      "RabbitTemplate", "convertAndSend(\"m\")",    MESSAGING),
            arguments(SpringMarkers.JMS_TEMPLATE,         "JmsTemplate",    "getConnectionFactory()",   MESSAGING),
            // CACHE
            arguments(SpringMarkers.CACHE_MANAGER,        "CacheManager",   "getCacheNames()",          CACHE)
        );
    }

    @ParameterizedTest(name = "{0} -> {3}")
    @MethodSource("clients")
    void GIVEN_source_calling_a_client_WHEN_extracting_THEN_the_call_is_tagged_with_its_kind(
            String importFqn, String simpleType, String invocation, ExternalCallType expected) {

        String source = """
            package com.example;

            import %s;

            public class Caller {
                private %s client;
                public void invoke() {
                    client.%s;
                }
            }
            """.formatted(importFqn, simpleType, invocation);

        MethodNode invoke = parse(source).applicationNodes().stream()
            .filter(n -> n.getMethodName().equals("invoke"))
            .findFirst()
            .orElseThrow();

        assertThat(invoke.getExternalCalls()).contains(expected);
    }

    private static CallGraph parse(String source) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(new VirtualFile(source, "Caller.java"));
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(cg);
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();
        return cg;
    }
}
