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

import com.codingful.carve.analyzer.TransactionAnalyzer;
import com.codingful.carve.analyzer.TransactionRisk;
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserDefinedMarkersTest {

    @Test
    void GIVEN_an_exact_fqn_marker_WHEN_detecting_THEN_it_is_classified_as_http() throws Exception {
        String props = "com.acme.payments.client.PaymentGatewayClient = HTTP\n";
        UserDefinedMarkers markers = loadFromString(props);

        assertThat(markers.detect(
            "com.acme.payments.client.PaymentGatewayClient"))
            .isEqualTo(ExternalCallType.HTTP);
    }

    @Test
    void GIVEN_a_package_prefix_marker_WHEN_detecting_THEN_it_matches_subclasses() throws Exception {
        String props = "com.acme.payments. = HTTP\n";
        UserDefinedMarkers markers = loadFromString(props);

        assertThat(markers.detect("com.acme.payments.client.RefundClient"))
            .isEqualTo(ExternalCallType.HTTP);
        assertThat(markers.detect("com.acme.payments.SomeOtherClass"))
            .isEqualTo(ExternalCallType.HTTP);
    }

    @Test
    void GIVEN_an_unknown_class_WHEN_detecting_THEN_it_returns_null() throws Exception {
        UserDefinedMarkers markers = loadFromString("com.acme.payments. = HTTP\n");
        assertThat(markers.detect("com.unrelated.SomeClass")).isNull();
    }

    @Test
    void GIVEN_a_vendor_sdk_marker_WHEN_analysing_a_transactional_call_through_it_THEN_it_is_flagged_as_an_http_risk()
            throws Exception {
        // The user declares a vendor SDK class (unknown to SpringMarkers) as an
        // HTTP client; a @Transactional call reaching it must surface as a risk.
        var risks = analyseVendorCheckout(
            "com.example.orders.PaymentGatewayClient = HTTP\n");

        assertThat(risks).isNotEmpty();
        assertThat(risks.get(0).callTypes()).contains(ExternalCallType.HTTP);
    }

    @Test
    void GIVEN_a_markers_file_with_a_typo_in_a_call_type_WHEN_analysing_THEN_the_bad_line_is_ignored_and_the_valid_marker_still_applies()
            throws Exception {
        // A user typo on one line must not abort loading: the misspelt type is
        // dropped, and the valid marker below it still flags the vendor call.
        var risks = analyseVendorCheckout(
            "com.example.orders.PaymentGatewayClient = HTTP\n"
          + "com.example.orders.OrderService = NOT_A_REAL_TYPE\n");

        assertThat(risks).isNotEmpty();
        assertThat(risks.get(0).callTypes()).contains(ExternalCallType.HTTP);
    }

    // -----------------------------------------------------------------------

    /**
     * Runs the full extract → analyse pipeline over a fixed sample where a
     * {@code @Transactional} service reaches a vendor SDK class, using the given
     * markers file content, and returns the transaction risks found.
     */
    private static List<TransactionRisk> analyseVendorCheckout(String markersContent) throws Exception {
        String source = """
            package com.example.orders;

            import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Transactional;

            @Component
            @Transactional
            class OrderService {

                private final PaymentProcessor processor = new PaymentProcessor();

                public void checkout() {
                    processor.process();
                }
            }

            @Component
            class PaymentProcessor {

                public void process() {
                    PaymentGatewayClient client = new PaymentGatewayClient();
                    client.charge();
                }
            }

            class PaymentGatewayClient {
                public Object charge() { return null; }  // HTTPS to the gateway in reality
            }
            """;

        Launcher launcher = new Launcher();
        launcher.addInputResource(new VirtualFile(source, "Orders.java"));
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        UserDefinedMarkers markers = loadFromString(markersContent);
        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(cg, markers);
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();

        return new TransactionAnalyzer(cg).findRisks();
    }

    // -----------------------------------------------------------------------

    private static UserDefinedMarkers loadFromString(String content) throws Exception {
        // The parsing factory is package-private; drive it through the public
        // fromFile entry point via a temporary .properties file.
        var tmp = java.nio.file.Files.createTempFile("markers", ".properties");
        java.nio.file.Files.writeString(tmp, content);
        try {
            return UserDefinedMarkers.fromFile(tmp);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
