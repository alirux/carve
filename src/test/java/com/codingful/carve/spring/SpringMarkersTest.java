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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SpringMarkersTest {

    // -----------------------------------------------------------------------
    // Transactional annotations
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_unrelated_annotation_WHEN_checking_transactional_THEN_false() {
        assertThat(SpringMarkers.isTransactionalAnnotation("com.example.NotTransactional")).isFalse();
    }

    // -----------------------------------------------------------------------
    // Component stereotypes
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "org.springframework.stereotype.Service,                  SERVICE",
        "org.springframework.stereotype.Repository,               REPOSITORY",
        "org.springframework.stereotype.Controller,               CONTROLLER",
        "org.springframework.web.bind.annotation.RestController,  REST_CONTROLLER",
        "org.springframework.context.annotation.Configuration,    CONFIGURATION",
        "org.springframework.stereotype.Component,                COMPONENT",
    })
    void GIVEN_a_stereotype_annotation_WHEN_mapping_THEN_the_component_type_is_returned(
            String fqn, SpringComponentType expected) {
        assertThat(SpringMarkers.componentTypeFor(fqn)).isEqualTo(expected);
    }

    @Test
    void GIVEN_a_non_stereotype_annotation_WHEN_mapping_THEN_null() {
        assertThat(SpringMarkers.componentTypeFor("com.example.NotAStereotype")).isNull();
    }

    // -----------------------------------------------------------------------
    // External call detection — simple-name fallback (type not fully resolved).
    // The fully-resolved FQN path is covered end-to-end from real source in
    // ExternalCallDetectionTest; here we pin the fallback and the edge cases.
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "com.vendor.RestTemplate,               HTTP",
        "RestTemplate,                          HTTP",   // no package → simpleNameOf no-dot branch
        "com.vendor.WebClient,                  HTTP",
        "com.vendor.RestClient,                 HTTP",
        "com.vendor.HttpClient,                 HTTP",
        "com.vendor.OkHttpClient,               HTTP",
        "com.vendor.JdbcTemplate,               JDBC",
        "com.vendor.NamedParameterJdbcTemplate, JDBC",
        "com.vendor.SqlSession,                 JDBC",
        "com.vendor.SqlSessionTemplate,         JDBC",
        "com.vendor.EntityManager,              JPA",
        "com.vendor.KafkaTemplate,              MESSAGING",
        "com.vendor.RabbitTemplate,             MESSAGING",
        "com.vendor.JmsTemplate,                MESSAGING",
    })
    void GIVEN_an_unresolved_type_with_a_known_simple_name_WHEN_detecting_THEN_fallback_returns_the_type(
            String fqn, ExternalCallType expected) {
        assertThat(SpringMarkers.detectExternalCallType(fqn)).isEqualTo(expected);
    }

    @Test
    void GIVEN_a_null_or_unknown_type_WHEN_detecting_THEN_null() {
        assertThat(SpringMarkers.detectExternalCallType(null)).isNull();
        assertThat(SpringMarkers.detectExternalCallType("com.example.PlainService")).isNull();
        assertThat(SpringMarkers.detectExternalCallType("PlainService")).isNull(); // no-dot, unknown simple name
    }

    // -----------------------------------------------------------------------
    // Spring Data repositories
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_ordinary_interface_WHEN_checking_spring_data_repository_THEN_false() {
        assertThat(SpringMarkers.isSpringDataRepository("com.example.MyRepository")).isFalse();
    }

    // -----------------------------------------------------------------------
    // Propagation parsing
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_valid_propagation_name_WHEN_parsing_THEN_it_is_mapped() {
        assertThat(SpringMarkers.parsePropagation("REQUIRES_NEW"))
            .isEqualTo(TransactionPropagation.REQUIRES_NEW);
    }

    @Test
    void GIVEN_an_unknown_propagation_name_WHEN_parsing_THEN_it_defaults_to_required() {
        assertThat(SpringMarkers.parsePropagation("NOT_A_PROPAGATION"))
            .isEqualTo(TransactionPropagation.REQUIRED);
    }
}
