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

/**
 * Categories of calls that cross a process or network boundary.
 * Used to flag methods that introduce I/O risk inside a transaction.
 */
public enum ExternalCallType {

    /** HTTP via RestTemplate, WebClient, RestClient, Feign, java.net.http, OkHttp, Apache HttpClient */
    HTTP,

    /** Raw JDBC via JdbcTemplate or NamedParameterJdbcTemplate */
    JDBC,

    /** JPA / Hibernate via EntityManager or Spring Data repositories */
    JPA,

    /** Async messaging: Kafka, RabbitMQ, JMS */
    MESSAGING,

    /** Cache operations (Caffeine, Redis, Hazelcast) via Spring Cache abstraction */
    CACHE
}
