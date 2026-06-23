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

package com.codingful.carve.analyzer;

import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.codingful.carve.support.TestNodes.app;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionRiskTest {

    private static TransactionRisk risk(MethodNode root, MethodNode site, Set<ExternalCallType> types) {
        return new TransactionRisk(root, site, types, List.of(root, site));
    }

    @Test
    void GIVEN_an_http_call_WHEN_checking_kinds_THEN_http_is_involved_but_not_messaging() {
        var r = risk(app("root").build(), app("site").build(), Set.of(ExternalCallType.HTTP));

        assertThat(r.involvesHttp()).isTrue();
        assertThat(r.involvesMessaging()).isFalse();
    }

    @Test
    void GIVEN_a_messaging_call_WHEN_checking_kinds_THEN_messaging_is_involved_but_not_http() {
        var r = risk(app("root").build(), app("site").build(), Set.of(ExternalCallType.MESSAGING));

        assertThat(r.involvesMessaging()).isTrue();
        assertThat(r.involvesHttp()).isFalse();
    }

    @Test
    void GIVEN_a_risk_WHEN_to_string_THEN_it_renders_root_site_and_call_types() {
        var r = risk(app("root").build(), app("site").build(), EnumSet.of(ExternalCallType.HTTP));

        // The set's own toString already carries brackets, hence the doubled pair.
        assertThat(r).hasToString("root --> site [[HTTP]]");
    }
}
