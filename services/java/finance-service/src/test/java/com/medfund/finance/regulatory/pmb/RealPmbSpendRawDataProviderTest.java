package com.medfund.finance.regulatory.pmb;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.PmbBeneficiaryCountResponse;
import com.medfund.finance.dto.PmbPaidAggregateRow;
import com.medfund.finance.regulatory.pmb.PmbSchemeIdentityReader.SchemeIdentity;
import com.medfund.finance.regulatory.service.RegulatoryFxPolicy;
import com.medfund.shared.report.ReportKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealPmbSpendRawDataProviderTest {

    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 12, 31);

    @Mock ClaimsClient claimsClient;
    @Mock UserServiceClient userServiceClient;
    @Mock RegulatoryFxPolicy fxPolicy;
    @Mock PmbSchemeIdentityReader identityReader;

    private RealPmbSpendRawDataProvider provider() {
        return new RealPmbSpendRawDataProvider(claimsClient, userServiceClient, fxPolicy, identityReader);
    }

    @Test
    void load_bucketsConditionCodesIntoCategories_andNonPmbIntoNonPmbBucket() {
        when(claimsClient.pmbPaid(PERIOD_START, PERIOD_END)).thenReturn(Mono.just(List.of(
                pmb("PMB-001", "ZAR", "1000.00", 3L),   // RESPIRATORY
                pmb("PMB-025", "ZAR", "500.00", 2L),    // CARDIAC
                pmb("PMB-060", "ZAR", "750.00", 1L),    // RENAL
                nonPmb("ZAR", "9000.00", 12L))));
        when(userServiceClient.pmbBeneficiaryCount(PERIOD_END))
                .thenReturn(Mono.just(new PmbBeneficiaryCountResponse(80L, 20L, 100L)));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new SchemeIdentity("Acme Scheme", "CMS-42")));

        StepVerifier.create(provider().load(TENANT, PERIOD_START, PERIOD_END))
                .assertNext(raw -> {
                    assertThat(raw.schemeName()).isEqualTo("Acme Scheme");
                    assertThat(raw.registrationNumber()).isEqualTo("CMS-42");
                    assertThat(raw.totalBeneficiaries()).isEqualTo(100L);
                    assertThat(raw.pmbPaidByCategory().get(PmbCategory.RESPIRATORY))
                            .isEqualByComparingTo("1000.00");
                    assertThat(raw.pmbPaidByCategory().get(PmbCategory.CARDIAC))
                            .isEqualByComparingTo("500.00");
                    assertThat(raw.pmbPaidByCategory().get(PmbCategory.RENAL))
                            .isEqualByComparingTo("750.00");
                    assertThat(raw.pmbPaidByCategory().get(PmbCategory.ONCOLOGY))
                            .isEqualByComparingTo("0");
                    assertThat(raw.pmbCountByCategory().get(PmbCategory.CARDIAC)).isEqualTo(2L);
                    assertThat(raw.nonPmbPaid()).isEqualByComparingTo("9000.00");
                })
                .verifyComplete();
    }

    @Test
    void load_convertsNonZarRowsThroughFxPolicy_beforeSumming() {
        // Two RESPIRATORY rows: one already ZAR, one in USD that FX doubles to ZAR.
        when(claimsClient.pmbPaid(PERIOD_START, PERIOD_END)).thenReturn(Mono.just(List.of(
                pmb("PMB-005", "ZAR", "100.00", 1L),
                pmb("PMB-010", "USD", "50.00", 1L))));
        when(userServiceClient.pmbBeneficiaryCount(PERIOD_END))
                .thenReturn(Mono.just(new PmbBeneficiaryCountResponse(0L, 0L, 10L)));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new SchemeIdentity("S", "R")));
        when(fxPolicy.convert(eq(ReportKey.PMB_SPEND), eq(TENANT), eq(PERIOD_END),
                any(BigDecimal.class), eq("USD"), eq("ZAR")))
                .thenReturn(Mono.just(new BigDecimal("100.00")));

        StepVerifier.create(provider().load(TENANT, PERIOD_START, PERIOD_END))
                .assertNext(raw -> assertThat(raw.pmbPaidByCategory().get(PmbCategory.RESPIRATORY))
                        .isEqualByComparingTo("200.00"))
                .verifyComplete();

        verify(fxPolicy).convert(eq(ReportKey.PMB_SPEND), eq(TENANT), eq(PERIOD_END),
                eq(new BigDecimal("50.00")), eq("USD"), eq("ZAR"));
    }

    @Test
    void load_unknownConditionCode_fallsToOtherCategory() {
        when(claimsClient.pmbPaid(PERIOD_START, PERIOD_END)).thenReturn(Mono.just(List.of(
                pmb("PMB-999", "ZAR", "42.00", 1L),   // 999/10 = 99 → OTHER
                pmb(null,      "ZAR", "10.00", 1L)))); // isPmb but no code → OTHER
        when(userServiceClient.pmbBeneficiaryCount(PERIOD_END))
                .thenReturn(Mono.just(new PmbBeneficiaryCountResponse(0L, 0L, 5L)));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new SchemeIdentity("S", "R")));

        StepVerifier.create(provider().load(TENANT, PERIOD_START, PERIOD_END))
                .assertNext(raw -> {
                    assertThat(raw.pmbPaidByCategory().get(PmbCategory.OTHER))
                            .isEqualByComparingTo("52.00");
                    assertThat(raw.pmbCountByCategory().get(PmbCategory.OTHER)).isEqualTo(2L);
                })
                .verifyComplete();
    }

    @Test
    void load_peerFailures_returnFallbacksWithZeroTotals() {
        // Both peer calls fail. CrossServiceCallHelper substitutes an empty list
        // + zero-beneficiary response so the report renders with a warning
        // trail (fail-loud on regulator submission would be another sub-phase).
        when(claimsClient.pmbPaid(PERIOD_START, PERIOD_END))
                .thenReturn(Mono.error(new RuntimeException("claims down")));
        when(userServiceClient.pmbBeneficiaryCount(PERIOD_END))
                .thenReturn(Mono.error(new RuntimeException("user down")));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new SchemeIdentity("S", "R")));

        StepVerifier.create(provider().load(TENANT, PERIOD_START, PERIOD_END))
                .assertNext(raw -> {
                    assertThat(raw.totalBeneficiaries()).isZero();
                    assertThat(raw.totalPmbPaid()).isEqualByComparingTo("0");
                    assertThat(raw.nonPmbPaid()).isEqualByComparingTo("0");
                    assertThat(raw.totalPmbCount()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void load_zarCurrency_bypassesFxPolicyEntirely() {
        lenient().when(fxPolicy.convert(any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("must not be called for ZAR")));
        when(claimsClient.pmbPaid(PERIOD_START, PERIOD_END)).thenReturn(Mono.just(List.of(
                pmb("PMB-002", "ZAR", "77.00", 4L))));
        when(userServiceClient.pmbBeneficiaryCount(PERIOD_END))
                .thenReturn(Mono.just(new PmbBeneficiaryCountResponse(0L, 0L, 1L)));
        when(identityReader.load(TENANT))
                .thenReturn(Mono.just(new SchemeIdentity("S", "R")));

        StepVerifier.create(provider().load(TENANT, PERIOD_START, PERIOD_END))
                .assertNext(raw -> assertThat(raw.pmbPaidByCategory().get(PmbCategory.RESPIRATORY))
                        .isEqualByComparingTo("77.00"))
                .verifyComplete();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static PmbPaidAggregateRow pmb(String code, String currency, String amount, long count) {
        return new PmbPaidAggregateRow(Boolean.TRUE, code, currency, new BigDecimal(amount), count);
    }

    private static PmbPaidAggregateRow nonPmb(String currency, String amount, long count) {
        return new PmbPaidAggregateRow(Boolean.FALSE, null, currency, new BigDecimal(amount), count);
    }
}
