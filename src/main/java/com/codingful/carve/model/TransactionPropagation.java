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
 * Mirrors {@code org.springframework.transaction.annotation.Propagation}.
 * Kept as a local enum so the model has no Spring runtime dependency.
 */
public enum TransactionPropagation {

    /** Join existing tx or create one. Default Spring behaviour. */
    REQUIRED,

    /** Always create a new tx; suspend any existing one. */
    REQUIRES_NEW,

    /** Join existing tx if present; run non-transactionally if not. */
    SUPPORTS,

    /** Suspend any existing tx; always run non-transactionally. */
    NOT_SUPPORTED,

    /** Fail if no existing tx is active. */
    MANDATORY,

    /** Fail if an existing tx is active. */
    NEVER,

    /** Nested savepoint inside existing tx (JDBC only). */
    NESTED
}
