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

import com.codingful.carve.graph.CallGraph;
import com.codingful.carve.model.ExternalCallType;
import com.codingful.carve.model.MethodNode;
import com.codingful.carve.model.SpringComponentType;
import com.codingful.carve.model.TransactionPropagation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionAnalyzerTest {

    /**
     * Builds a graph manually (no Spoon) so tests are fast and isolated.
     */
    private static MethodNode appNode(String id, boolean tx, TransactionPropagation prop) {
        MethodNode n = new MethodNode(id, "com.example", "Cls", id, "com.example.Cls", true);
        n.setTransactional(tx);
        n.setPropagation(prop);
        return n;
    }

    private static MethodNode httpNode(String id) {
        MethodNode n = new MethodNode(id, "com.example", "Cls", id, "com.example.Cls", true);
        n.addExternalCall(ExternalCallType.HTTP);
        return n;
    }

    // -----------------------------------------------------------------------

    @Test
    void GIVEN_an_http_call_reachable_from_a_transactional_root_WHEN_analysing_THEN_a_risk_is_detected() {
        CallGraph cg = new CallGraph();

        MethodNode root     = appNode("placeOrder", true, TransactionPropagation.REQUIRED);
        MethodNode middle   = appNode("fetchInventory", false, TransactionPropagation.REQUIRED);
        MethodNode httpSite = httpNode("callWarehouseApi");

        cg.addVertex(root);
        cg.addVertex(middle);
        cg.addVertex(httpSite);
        cg.addEdge(root, middle);
        cg.addEdge(middle, httpSite);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).transactionalRoot()).isEqualTo(root);
        assertThat(risks.get(0).externalCallSite()).isEqualTo(httpSite);
        assertThat(risks.get(0).callTypes()).contains(ExternalCallType.HTTP);
        assertThat(risks.get(0).path()).containsExactly(root, middle, httpSite);
    }

    @Test
    void GIVEN_a_requires_new_boundary_WHEN_analysing_THEN_it_stops_transaction_scope_propagation() {
        CallGraph cg = new CallGraph();

        MethodNode root        = appNode("outerTx", true, TransactionPropagation.REQUIRED);
        MethodNode newTxMethod = appNode("innerNewTx", true, TransactionPropagation.REQUIRES_NEW);
        MethodNode httpSite    = httpNode("callExternal");

        cg.addVertex(root);
        cg.addVertex(newTxMethod);
        cg.addVertex(httpSite);
        cg.addEdge(root, newTxMethod);
        cg.addEdge(newTxMethod, httpSite);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        // The HTTP call is inside REQUIRES_NEW, not inside outerTx → no risk from root
        // (innerNewTx starts its own scope, so it should itself appear as a root)
        assertThat(risks).noneMatch(r -> r.transactionalRoot().equals(root));
    }

    @Test
    void GIVEN_an_intermediate_transactional_that_joins_the_scope_WHEN_analysing_THEN_the_risk_still_reaches_the_root() {
        CallGraph cg = new CallGraph();

        MethodNode root  = appNode("outerTx", true, TransactionPropagation.REQUIRED);
        // MANDATORY joins the caller's transaction rather than starting a new one,
        // so the scope continues through it — unlike REQUIRES_NEW above.
        MethodNode inner = appNode("innerJoins", true, TransactionPropagation.MANDATORY);
        MethodNode httpSite = httpNode("callExternal");

        cg.addVertex(root);
        cg.addVertex(inner);
        cg.addVertex(httpSite);
        cg.addEdge(root, inner);
        cg.addEdge(inner, httpSite);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        // The call is reachable from root because MANDATORY did not break the scope.
        assertThat(risks).anyMatch(r ->
            r.transactionalRoot().equals(root) && r.externalCallSite().equals(httpSite));
    }

    @Test
    void GIVEN_an_external_call_reachable_by_two_paths_from_one_root_WHEN_analysing_THEN_it_is_reported_once() {
        CallGraph cg = new CallGraph();

        MethodNode root = appNode("handle", true, TransactionPropagation.REQUIRED);
        MethodNode viaA = appNode("viaA", false, TransactionPropagation.REQUIRED);
        MethodNode viaB = appNode("viaB", false, TransactionPropagation.REQUIRED);
        MethodNode httpSite = httpNode("callGateway");

        cg.addVertex(root);
        cg.addVertex(viaA);
        cg.addVertex(viaB);
        cg.addVertex(httpSite);
        cg.addEdge(root, viaA);
        cg.addEdge(root, viaB);
        cg.addEdge(viaA, httpSite);   // diamond: httpSite reachable two ways
        cg.addEdge(viaB, httpSite);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        // The shared call site is visited once, so the risk is not double-counted.
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).externalCallSite()).isEqualTo(httpSite);
    }

    @Test
    void GIVEN_an_http_call_outside_any_transaction_WHEN_analysing_THEN_no_risk() {
        CallGraph cg = new CallGraph();

        MethodNode nonTx   = appNode("doStuff", false, TransactionPropagation.REQUIRED);
        MethodNode httpSite = httpNode("callApi");

        cg.addVertex(nonTx);
        cg.addVertex(httpSite);
        cg.addEdge(nonTx, httpSite);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        assertThat(risks).isEmpty();
    }

    @Test
    void GIVEN_a_jdbc_call_inside_a_transaction_WHEN_analysing_THEN_it_is_not_a_risk() {
        CallGraph cg = new CallGraph();

        MethodNode root = appNode("save", true, TransactionPropagation.REQUIRED);
        MethodNode jdbc = new MethodNode("jdbcInsert", "com.example", "Repo",
            "insert", "com.example.Repo", true);
        jdbc.addExternalCall(ExternalCallType.JDBC);

        cg.addVertex(root);
        cg.addVertex(jdbc);
        cg.addEdge(root, jdbc);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        assertThat(risks).isEmpty();
    }

    @Test
    void GIVEN_a_messaging_call_inside_a_transaction_WHEN_analysing_THEN_it_is_a_risk() {
        CallGraph cg = new CallGraph();

        MethodNode root = appNode("processOrder", true, TransactionPropagation.REQUIRED);
        MethodNode kafka = new MethodNode("sendEvent", "com.example", "Publisher",
            "sendEvent", "com.example.Publisher", true);
        kafka.addExternalCall(ExternalCallType.MESSAGING);

        cg.addVertex(root);
        cg.addVertex(kafka);
        cg.addEdge(root, kafka);

        List<TransactionRisk> risks = new TransactionAnalyzer(cg).findRisks();

        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).callTypes()).contains(ExternalCallType.MESSAGING);
    }
}
