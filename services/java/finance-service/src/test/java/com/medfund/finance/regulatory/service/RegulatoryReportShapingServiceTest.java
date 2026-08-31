package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.tenant.TenantMetadataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatoryReportShapingServiceTest {

    @Mock
    private TenantMetadataReader tenantMetadata;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);

    /** In-test stub for a per-regulator shaper that just records the call + returns a canned shape. */
    private static class StubShaper implements PerRegulatorShaper {
        private final ReportKey key;
        private final String reportingCurrency;
        int shapeCalls;
        String seenCountry;

        StubShaper(ReportKey key, String reportingCurrency) {
            this.key = key;
            this.reportingCurrency = reportingCurrency;
        }

        @Override public ReportKey supportedKey() { return key; }

        @Override
        public Mono<RegulatoryReportData> shape(UUID tenantId, LocalDate ps, LocalDate pe,
                                                String tenantCountryCode) {
            shapeCalls++;
            seenCountry = tenantCountryCode;
            return Mono.just(RegulatoryReportData
                    .builder(key, tenantId, ps, pe, reportingCurrency)
                    .section("stub", Map.of("hello", "world"))
                    .build());
        }
    }

    @Test
    void constructor_duplicateKey_throws() {
        StubShaper a = new StubShaper(ReportKey.IPEC_QUARTERLY_RETURN, "ZWL");
        StubShaper b = new StubShaper(ReportKey.IPEC_QUARTERLY_RETURN, "ZWL");
        assertThatThrownBy(() -> new RegulatoryReportShapingService(tenantMetadata, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IPEC_QUARTERLY_RETURN");
    }

    @Test
    void shape_dispatchesToRegisteredShaperWithTenantCountry() {
        StubShaper ipec = new StubShaper(ReportKey.IPEC_QUARTERLY_RETURN, "ZWL");
        var service = new RegulatoryReportShapingService(tenantMetadata, List.of(ipec));
        when(tenantMetadata.load(TENANT_ID))
                .thenReturn(Mono.just(new TenantMetadataReader.TenantMetadata(
                        "ZW_IPEC_SHORT_TERM", "ZW")));

        StepVerifier.create(service.shape(
                        ReportKey.IPEC_QUARTERLY_RETURN, TENANT_ID, PERIOD_START, PERIOD_END))
                .assertNext(data -> {
                    assertThat(data.reportKey()).isEqualTo(ReportKey.IPEC_QUARTERLY_RETURN);
                    assertThat(data.tenantId()).isEqualTo(TENANT_ID);
                    assertThat(data.reportingCurrency()).isEqualTo("ZWL");
                    assertThat(data.sections()).containsKey("stub");
                })
                .verifyComplete();
        assertThat(ipec.shapeCalls).isEqualTo(1);
        assertThat(ipec.seenCountry).isEqualTo("ZW");
    }

    @Test
    void shape_unregisteredKey_returns501() {
        // Register only IPEC; ask for CMS_ASR.
        StubShaper ipec = new StubShaper(ReportKey.IPEC_QUARTERLY_RETURN, "ZWL");
        var service = new RegulatoryReportShapingService(tenantMetadata, List.of(ipec));

        StepVerifier.create(service.shape(
                        ReportKey.CMS_ASR, TENANT_ID, PERIOD_START, PERIOD_END))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(501);
                    assertThat(err.getMessage()).contains("CMS_ASR");
                })
                .verify();
    }

    @Test
    void rejectClientCurrencyOverride_ipecOverride_throws422() {
        assertThatThrownBy(() -> RegulatoryReportShapingService.rejectClientCurrencyOverride(
                ReportKey.IPEC_QUARTERLY_RETURN, "USD"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                    assertThat(err.getMessage()).contains("IPEC_QUARTERLY_RETURN");
                });
    }

    @Test
    void rejectClientCurrencyOverride_countryNativeOverride_throws422() {
        assertThatThrownBy(() -> RegulatoryReportShapingService.rejectClientCurrencyOverride(
                ReportKey.VAT_RETURN, "EUR"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectClientCurrencyOverride_nullOrBlank_passes() {
        // Regulator reports called without a currency override are fine — the resolver picks it.
        RegulatoryReportShapingService.rejectClientCurrencyOverride(ReportKey.IPEC_QUARTERLY_RETURN, null);
        RegulatoryReportShapingService.rejectClientCurrencyOverride(ReportKey.IPEC_QUARTERLY_RETURN, "");
        RegulatoryReportShapingService.rejectClientCurrencyOverride(ReportKey.IPEC_QUARTERLY_RETURN, "   ");
    }

    @Test
    void rejectClientCurrencyOverride_nonPhase16Key_passes() {
        // A non-Phase-16 key isn't gated by regulator rules — override is fine.
        RegulatoryReportShapingService.rejectClientCurrencyOverride(ReportKey.BILLING_REPORT, "USD");
    }

    @Test
    void registeredKeys_exposesRegistrySnapshot() {
        StubShaper ipec = new StubShaper(ReportKey.IPEC_QUARTERLY_RETURN, "ZWL");
        StubShaper cms = new StubShaper(ReportKey.CMS_ASR, "ZAR");
        var service = new RegulatoryReportShapingService(tenantMetadata, List.of(ipec, cms));

        assertThat(service.registeredKeys())
                .containsExactlyInAnyOrder(ReportKey.IPEC_QUARTERLY_RETURN, ReportKey.CMS_ASR);
    }
}
