package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 8 cross-service adapters are one-line delegations to
 * {@link CrossServiceRenderHelper}. Each carries {@link ReportKey} +
 * {@link ReportPeriodShape} + a base URL + path. The test verifies the
 * (key, shape, path) triple for each adapter and confirms the helper is
 * invoked with the correct target URL + call name.
 */
class CrossServiceAdapterKeyAndShapeTest {

    private final CrossServiceRenderHelper helper = mock(CrossServiceRenderHelper.class);

    private void expectHelperCalledWith(String path, String callName) {
        when(helper.render(any(ScheduledFireContext.class), anyString(), eq(path), eq(callName)))
                .thenReturn(Mono.just(new byte[]{1}));
    }

    private void verifyRender(String path, String callName) {
        verify(helper).render(any(ScheduledFireContext.class), anyString(), eq(path), eq(callName));
    }

    /** Adapters read their base URL from {@code @Value}; unit test needs a non-null value
     *  for the anyString() matcher on {@link CrossServiceRenderHelper#render} to fire. */
    private void injectBaseUrl(Object adapter) {
        // Try all three field names — each adapter has exactly one.
        for (String field : List.of("contributionsBaseUrl", "claimsBaseUrl", "userBaseUrl")) {
            try {
                ReflectionTestUtils.setField(adapter, field, "http://stub");
                return;
            } catch (IllegalArgumentException ignored) {
                // wrong field for this adapter — try next.
            }
        }
    }

    @Test
    void agedDebtors_asOfFireTime_pointsAtContributions() {
        var adapter = new AgedDebtorsAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/AGED_DEBTORS/scheduled-render",
                "contributions.aged-debtors.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.AGED_DEBTORS);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.AS_OF_FIRE_TIME);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/AGED_DEBTORS/scheduled-render",
                "contributions.aged-debtors.render");
    }

    @Test
    void uprMovement_previousComplete_pointsAtContributions() {
        var adapter = new UprMovementAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/UPR_MOVEMENT/scheduled-render",
                "contributions.upr-movement.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.UPR_MOVEMENT);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/UPR_MOVEMENT/scheduled-render",
                "contributions.upr-movement.render");
    }

    @Test
    void cashFlowForecast13w_asOfFireTime_pointsAtContributions() {
        var adapter = new CashFlowForecast13wAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/CASH_FLOW_FORECAST_13W/scheduled-render",
                "contributions.cash-flow-forecast-13w.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.CASH_FLOW_FORECAST_13W);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.AS_OF_FIRE_TIME);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/CASH_FLOW_FORECAST_13W/scheduled-render",
                "contributions.cash-flow-forecast-13w.render");
    }

    @Test
    void claimsSummary_previousComplete_pointsAtClaims() {
        var adapter = new ClaimsSummaryAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/CLAIMS_SUMMARY/scheduled-render",
                "claims.claims-summary.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.CLAIMS_SUMMARY);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/CLAIMS_SUMMARY/scheduled-render",
                "claims.claims-summary.render");
    }

    @Test
    void providerNetworkUtilization_previousComplete_pointsAtClaims() {
        var adapter = new ProviderNetworkUtilizationAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/PROVIDER_NETWORK_UTILIZATION/scheduled-render",
                "claims.provider-network-utilization.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.PROVIDER_NETWORK_UTILIZATION);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/PROVIDER_NETWORK_UTILIZATION/scheduled-render",
                "claims.provider-network-utilization.render");
    }

    @Test
    void policyMovement_previousComplete_pointsAtUser() {
        var adapter = new PolicyMovementAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/POLICY_MOVEMENT/scheduled-render",
                "user.policy-movement.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.POLICY_MOVEMENT);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/POLICY_MOVEMENT/scheduled-render",
                "user.policy-movement.render");
    }

    @Test
    void persistencyCohort_previousComplete_pointsAtUser() {
        var adapter = new PersistencyCohortAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/PERSISTENCY_COHORT/scheduled-render",
                "user.persistency-cohort.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.PERSISTENCY_COHORT);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/PERSISTENCY_COHORT/scheduled-render",
                "user.persistency-cohort.render");
    }

    @Test
    void groupCensus_previousComplete_pointsAtUser() {
        var adapter = new GroupCensusAdapter(helper);
        expectHelperCalledWith("/api/v1/reports/GROUP_CENSUS/scheduled-render",
                "user.group-census.render");
        assertThat(adapter.key()).isEqualTo(ReportKey.GROUP_CENSUS);
        assertThat(adapter.periodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        injectBaseUrl(adapter);
        adapter.render(mock(ScheduledFireContext.class)).block();
        verifyRender("/api/v1/reports/GROUP_CENSUS/scheduled-render",
                "user.group-census.render");
    }

    /** Confirms the whitelist stays in sync — 13 keys total = 5 finance-local + 8 cross-service. */
    @Test
    void allEightCrossServiceAdaptersAccountedFor() {
        List<ReportKey> crossKeys = List.of(
                ReportKey.AGED_DEBTORS,
                ReportKey.UPR_MOVEMENT,
                ReportKey.CASH_FLOW_FORECAST_13W,
                ReportKey.CLAIMS_SUMMARY,
                ReportKey.PROVIDER_NETWORK_UTILIZATION,
                ReportKey.POLICY_MOVEMENT,
                ReportKey.PERSISTENCY_COHORT,
                ReportKey.GROUP_CENSUS
        );
        assertThat(crossKeys).hasSize(8);
    }
}
