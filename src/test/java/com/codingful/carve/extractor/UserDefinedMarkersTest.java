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
import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserDefinedMarkersTest {

    @Test
    void exactFqnMatchIsDetectedAsHttp() throws Exception {
        String props = "com.acme.payments.client.PaymentGatewayClient = HTTP\n";
        UserDefinedMarkers markers = loadFromString(props);

        assertThat(markers.detect(
            "com.acme.payments.client.PaymentGatewayClient"))
            .isEqualTo(ExternalCallType.HTTP);
    }

    @Test
    void packagePrefixMatchesSubclasses() throws Exception {
        String props = "com.acme.payments. = HTTP\n";
        UserDefinedMarkers markers = loadFromString(props);

        assertThat(markers.detect("com.acme.payments.client.RefundClient"))
            .isEqualTo(ExternalCallType.HTTP);
        assertThat(markers.detect("com.acme.payments.SomeOtherClass"))
            .isEqualTo(ExternalCallType.HTTP);
    }

    @Test
    void unknownClassReturnsNull() throws Exception {
        UserDefinedMarkers markers = loadFromString("com.acme.payments. = HTTP\n");
        assertThat(markers.detect("com.unrelated.SomeClass")).isNull();
    }

    @Test
    void vendorSdkInsideTransactionIsDetectedAsRisk() throws Exception {
        // A @Transactional service calls a vendor SDK that makes HTTPS calls.
        // The SDK class is not in SpringMarkers; it's declared in a custom markers file.
        String source = """
            package com.example.orders;

            import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.beans.factory.annotation.Autowired;

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
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setShouldCompile(false);
        CtModel model = launcher.buildModel();

        // Tell the analyser that PaymentGatewayClient is an HTTP client
        String props = "com.example.orders.PaymentGatewayClient = HTTP\n";
        UserDefinedMarkers markers = loadFromString(props);

        CallGraph cg = new CallGraph();
        CallGraphExtractor extractor = new CallGraphExtractor(cg, markers);
        model.getAllTypes().forEach(extractor::scan);
        extractor.resolveInterfaceCalls();

        var risks = new TransactionAnalyzer(cg).findRisks();

        assertThat(risks).isNotEmpty();
        assertThat(risks.get(0).callTypes()).contains(ExternalCallType.HTTP);
    }

    // -----------------------------------------------------------------------

    private static UserDefinedMarkers loadFromString(String content) throws Exception {
        var in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        // Reflective call to the package-private fromStream equivalent via fromClasspath
        // — use the public fromFile factory via a temp file instead.
        var tmp = java.nio.file.Files.createTempFile("markers", ".properties");
        java.nio.file.Files.writeString(tmp, content);
        try {
            return UserDefinedMarkers.fromFile(tmp);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
