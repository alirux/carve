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

package com.codingful.carve.spring;

import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.SpringComponentType;
import com.codingful.carve.model.TransactionPropagation;

import java.util.Map;
import java.util.Set;

/**
 * Central registry of Spring (and related) fully qualified class names and
 * annotation names used throughout the analysis.
 *
 * Using string constants (rather than Class references) keeps this module
 * free from a Spring runtime dependency and lets it analyse any Spring version
 * without classpath conflicts.
 */
public final class SpringMarkers {

    private SpringMarkers() {}

    // -----------------------------------------------------------------------
    // Transaction annotations  (Spring + javax + jakarta)
    // -----------------------------------------------------------------------

    public static final String TX_SPRING  = "org.springframework.transaction.annotation.Transactional";
    public static final String TX_JAVAX   = "javax.transaction.Transactional";
    public static final String TX_JAKARTA = "jakarta.transaction.Transactional";

    public static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of(
        TX_SPRING, TX_JAVAX, TX_JAKARTA
    );

    // -----------------------------------------------------------------------
    // Spring component stereotype annotations
    // -----------------------------------------------------------------------

    private static final Map<String, SpringComponentType> COMPONENT_ANNOTATIONS = Map.of(
        "org.springframework.stereotype.Service",                      SpringComponentType.SERVICE,
        "org.springframework.stereotype.Repository",                   SpringComponentType.REPOSITORY,
        "org.springframework.stereotype.Controller",                   SpringComponentType.CONTROLLER,
        "org.springframework.web.bind.annotation.RestController",      SpringComponentType.REST_CONTROLLER,
        "org.springframework.context.annotation.Configuration",        SpringComponentType.CONFIGURATION,
        "org.springframework.stereotype.Component",                    SpringComponentType.COMPONENT
    );

    // -----------------------------------------------------------------------
    // HTTP clients
    // -----------------------------------------------------------------------

    /** Spring 3.x classic sync client */
    public static final String REST_TEMPLATE = "org.springframework.web.client.RestTemplate";
    /** Spring 5+ reactive client */
    public static final String WEB_CLIENT    = "org.springframework.web.reactive.function.client.WebClient";
    /** Spring 6.1+ fluent sync client */
    public static final String REST_CLIENT   = "org.springframework.web.client.RestClient";
    /** Spring Cloud OpenFeign */
    public static final String FEIGN_CLIENT_ANNOTATION = "org.springframework.cloud.openfeign.FeignClient";
    /** Native Java 11+ HTTP client */
    public static final String JAVA_HTTP_CLIENT = "java.net.http.HttpClient";
    /** OkHttp */
    public static final String OKHTTP_CLIENT    = "okhttp3.OkHttpClient";
    /** Apache HttpClient 4.x */
    public static final String APACHE_HTTP_CLIENT4 = "org.apache.http.client.HttpClient";
    /** Apache HttpClient 5.x */
    public static final String APACHE_HTTP_CLIENT5 = "org.apache.hc.client5.http.classic.HttpClient";

    // -----------------------------------------------------------------------
    // JDBC
    // -----------------------------------------------------------------------

    public static final String JDBC_TEMPLATE       = "org.springframework.jdbc.core.JdbcTemplate";
    public static final String NAMED_JDBC_TEMPLATE = "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate";

    // -----------------------------------------------------------------------
    // MyBatis
    // -----------------------------------------------------------------------

    /**
     * MyBatis low-level session API. In Spring applications this interface is
     * typically satisfied at runtime by a {@code SqlSessionTemplate} bean, but
     * the injection point is declared as {@code SqlSession}. Calls to
     * {@code selectOne / selectList / insert / update / delete} all hit the DB.
     */
    public static final String MYBATIS_SQL_SESSION  = "org.apache.ibatis.session.SqlSession";

    /**
     * Thread-safe Spring-managed wrapper around {@code SqlSessionFactory}.
     * Used as the concrete {@code SqlSession} bean in Spring XML configuration.
     */
    public static final String MYBATIS_SQL_SESSION_TEMPLATE = "org.mybatis.spring.SqlSessionTemplate";

    // -----------------------------------------------------------------------
    // JPA / Persistence
    // -----------------------------------------------------------------------

    public static final String ENTITY_MANAGER_JAVAX   = "javax.persistence.EntityManager";
    public static final String ENTITY_MANAGER_JAKARTA  = "jakarta.persistence.EntityManager";

    /** Base interfaces whose subtypes are Spring Data repositories */
    public static final Set<String> SPRING_DATA_REPO_INTERFACES = Set.of(
        "org.springframework.data.repository.Repository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.mongodb.repository.MongoRepository",
        "org.springframework.data.r2dbc.repository.R2dbcRepository"
    );

    // -----------------------------------------------------------------------
    // Messaging
    // -----------------------------------------------------------------------

    public static final String KAFKA_TEMPLATE  = "org.springframework.kafka.core.KafkaTemplate";
    public static final String RABBIT_TEMPLATE = "org.springframework.amqp.rabbit.core.RabbitTemplate";
    public static final String JMS_TEMPLATE    = "org.springframework.jms.core.JmsTemplate";

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    public static final String CACHE_MANAGER  = "org.springframework.cache.CacheManager";
    public static final String CACHEABLE      = "org.springframework.cache.annotation.Cacheable";
    public static final String CACHE_EVICT    = "org.springframework.cache.annotation.CacheEvict";

    // -----------------------------------------------------------------------
    // Query helpers
    // -----------------------------------------------------------------------

    public static boolean isTransactionalAnnotation(String fqn) {
        return TRANSACTIONAL_ANNOTATIONS.contains(fqn);
    }

    /** Returns the component type if this annotation FQN is a Spring stereotype, null otherwise. */
    public static SpringComponentType componentTypeFor(String annotationFqn) {
        return COMPONENT_ANNOTATIONS.get(annotationFqn);
    }

    /**
     * Detects whether a declaring-type FQN represents a known external call.
     * Returns null if the type is not recognised.
     */
    public static ExternalCallType detectExternalCallType(String declaringTypeFqn) {
        if (declaringTypeFqn == null) return null;

        return switch (declaringTypeFqn) {
            case REST_TEMPLATE, WEB_CLIENT, REST_CLIENT,
                 JAVA_HTTP_CLIENT, OKHTTP_CLIENT,
                 APACHE_HTTP_CLIENT4, APACHE_HTTP_CLIENT5 -> ExternalCallType.HTTP;

            case JDBC_TEMPLATE, NAMED_JDBC_TEMPLATE,
                 MYBATIS_SQL_SESSION, MYBATIS_SQL_SESSION_TEMPLATE -> ExternalCallType.JDBC;

            case ENTITY_MANAGER_JAVAX, ENTITY_MANAGER_JAKARTA -> ExternalCallType.JPA;

            case KAFKA_TEMPLATE, RABBIT_TEMPLATE, JMS_TEMPLATE -> ExternalCallType.MESSAGING;

            case CACHE_MANAGER                             -> ExternalCallType.CACHE;

            default -> {
                // Simple-name fallback for when full type resolution is unavailable
                String simple = simpleNameOf(declaringTypeFqn);
                yield switch (simple) {
                    case "RestTemplate", "WebClient", "RestClient",
                         "HttpClient", "OkHttpClient"    -> ExternalCallType.HTTP;
                    case "JdbcTemplate", "NamedParameterJdbcTemplate",
                         "SqlSession", "SqlSessionTemplate" -> ExternalCallType.JDBC;
                    case "EntityManager"                 -> ExternalCallType.JPA;
                    case "KafkaTemplate", "RabbitTemplate", "JmsTemplate" -> ExternalCallType.MESSAGING;
                    default -> null;
                };
            }
        };
    }

    /** Returns whether the declaring type FQN is a Spring Data repository interface. */
    public static boolean isSpringDataRepository(String declaringTypeFqn) {
        return SPRING_DATA_REPO_INTERFACES.contains(declaringTypeFqn);
    }

    /**
     * Maps the Spring {@code Propagation} enum value name to our local enum.
     * Returns {@code REQUIRED} for unrecognised strings (matches Spring default).
     */
    public static TransactionPropagation parsePropagation(String value) {
        try {
            return TransactionPropagation.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TransactionPropagation.REQUIRED;
        }
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
