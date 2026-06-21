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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MethodNodeTest {

    private static MethodNode node(String id) {
        return new MethodNode(id, "com.example", "Foo", "bar", "com.example.Foo", true);
    }

    // -----------------------------------------------------------------------
    // Identity — based solely on id
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_two_nodes_with_same_id_but_different_fields_WHEN_comparing_THEN_equal_and_same_hashcode() {
        MethodNode a = new MethodNode("com.example.Foo#bar()", "com.example", "Foo", "bar", "com.example.Foo", true);
        // Same id, but every other field differs (incl. applicationCode flag).
        MethodNode b = new MethodNode("com.example.Foo#bar()", "other.pkg", "Baz", "qux", "other.pkg.Baz", false);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void GIVEN_nodes_with_different_ids_WHEN_comparing_THEN_not_equal() {
        assertThat(node("a")).isNotEqualTo(node("b"));
    }

    @Test
    void GIVEN_a_node_WHEN_compared_to_null_or_other_type_or_itself_THEN_behaves_correctly() {
        MethodNode n = node("a");
        assertThat(n).isNotEqualTo(null);
        assertThat(n).isNotEqualTo("a");
        assertThat(n).isEqualTo(n); // reflexive
    }

    @Test
    void GIVEN_a_node_WHEN_to_string_THEN_returns_id() {
        assertThat(node("com.example.Foo#bar()")).hasToString("com.example.Foo#bar()");
    }

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_freshly_constructed_node_WHEN_reading_state_THEN_defaults_are_sensible() {
        MethodNode n = node("a");
        assertThat(n.getProjectName()).isEmpty();
        assertThat(n.getComponentType()).isEqualTo(SpringComponentType.NONE);
        assertThat(n.isTransactional()).isFalse();
        assertThat(n.getPropagation()).isEqualTo(TransactionPropagation.REQUIRED);
        assertThat(n.isReadOnly()).isFalse();
        assertThat(n.makesExternalCall()).isFalse();
        assertThat(n.getExternalCalls()).isEmpty();
    }

    @Test
    void GIVEN_constructor_arguments_WHEN_reading_accessors_THEN_they_reflect_them() {
        MethodNode n = new MethodNode("id", "com.example", "Foo", "bar", "com.example.Foo", true);
        assertThat(n.getId()).isEqualTo("id");
        assertThat(n.getPackageName()).isEqualTo("com.example");
        assertThat(n.getClassName()).isEqualTo("Foo");
        assertThat(n.getMethodName()).isEqualTo("bar");
        assertThat(n.getDeclaringTypeFqn()).isEqualTo("com.example.Foo");
        assertThat(n.isApplicationCode()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Mutators
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_a_null_project_name_WHEN_set_THEN_collapses_to_empty_string() {
        MethodNode n = node("a");
        n.setProjectName("api");
        assertThat(n.getProjectName()).isEqualTo("api");
        n.setProjectName(null);
        assertThat(n.getProjectName()).isEmpty();
    }

    @Test
    void GIVEN_setter_calls_WHEN_applied_THEN_state_is_updated() {
        MethodNode n = node("a");
        n.setComponentType(SpringComponentType.SERVICE);
        n.setTransactional(true);
        n.setPropagation(TransactionPropagation.REQUIRES_NEW);
        n.setReadOnly(true);

        assertThat(n.getComponentType()).isEqualTo(SpringComponentType.SERVICE);
        assertThat(n.isTransactional()).isTrue();
        assertThat(n.getPropagation()).isEqualTo(TransactionPropagation.REQUIRES_NEW);
        assertThat(n.isReadOnly()).isTrue();
    }

    // -----------------------------------------------------------------------
    // External-call classification
    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_http_external_call_WHEN_classifying_THEN_flagged_as_http_and_external() {
        MethodNode n = node("a");
        n.addExternalCall(ExternalCallType.HTTP);
        assertThat(n.makesExternalCall()).isTrue();
        assertThat(n.makesHttpCall()).isTrue();
        assertThat(n.makesDbCall()).isFalse();
        assertThat(n.makesMessagingCall()).isFalse();
    }

    @Test
    void GIVEN_a_jdbc_or_jpa_call_WHEN_classifying_THEN_flagged_as_db_call() {
        MethodNode jdbc = node("jdbc");
        jdbc.addExternalCall(ExternalCallType.JDBC);
        assertThat(jdbc.makesDbCall()).isTrue();

        MethodNode jpa = node("jpa");
        jpa.addExternalCall(ExternalCallType.JPA);
        assertThat(jpa.makesDbCall()).isTrue();
    }

    @Test
    void GIVEN_a_messaging_call_WHEN_classifying_THEN_flagged_as_messaging() {
        MethodNode n = node("a");
        n.addExternalCall(ExternalCallType.MESSAGING);
        assertThat(n.makesMessagingCall()).isTrue();
    }

    @Test
    void GIVEN_external_calls_WHEN_modifying_the_returned_set_THEN_it_is_unmodifiable() {
        MethodNode n = node("a");
        n.addExternalCall(ExternalCallType.HTTP);
        assertThat(n.getExternalCalls()).containsExactly(ExternalCallType.HTTP);
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> n.getExternalCalls().add(ExternalCallType.JPA))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
