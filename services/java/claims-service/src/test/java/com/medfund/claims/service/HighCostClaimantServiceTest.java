package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import com.medfund.shared.config.TenantConfigClient;
import com.medfund.shared.report.FxRateReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HighCostClaimantService} (Phase 4 §A, G46).
 * Pins the threshold-convert → filter → sort pipeline, the config-gap
 * behaviour (empty report + warning, never an error), and the member
 * drill-down pagination wiring.
 */
@ExtendWith(MockitoExtension.class)
class HighCostClaimantServiceTest {

    @Mock private ClaimsReportQueryRepository repository;
    @Mock private TenantConfigClient tenantConfigClient;
    @Mock private FxRateReader fxRateReader;

    @InjectMocks private HighCostClaimantService service;

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);
    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void report_noConfig_isEmptyWithWarning() {
        when(tenantConfigClient.getHighCostClaimantConfig(TENANT)).thenReturn(Mono.empty());

        StepVerifier.create(service.report(START, END, "USD", TENANT))
                .assertNext(result -> {
                    assertThat(result.rows()).isEmpty();
                    assertThat(result.configWarning())
                            .isEqualTo("High-cost threshold not configured for tenant");
                })
                .verifyComplete();

        verifyNoInteractions(repository, fxRateReader);
    }

    @Test
    void report_convertsThresholdAndRowsFiltersAboveItAndSortsDesc() {
        TenantConfigClient.HighCostClaimantConfig cfg =
                new TenantConfigClient.HighCostClaimantConfig(new BigDecimal("1000.00"), "USD");
        when(tenantConfigClient.getHighCostClaimantConfig(TENANT)).thenReturn(Mono.just(cfg));
        when(fxRateReader.convert(eq(new BigDecimal("1000.00")), eq("USD"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("1000.00")));
        when(fxRateReader.convert(eq(new BigDecimal("1200.00")), eq("USD"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("1200.00")));
        when(fxRateReader.convert(eq(new BigDecimal("999.00")), eq("USD"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("999.00")));

        HighCostClaimantRow above = row("M-001", "Alice", "USD", "1200.00", 3);
        HighCostClaimantRow atThreshold = row("M-002", "Bob", "USD", "1000.00", 2);
        HighCostClaimantRow below = row("M-003", "Carol", "USD", "999.00", 1);
        when(repository.highCostMemberTotals(START, END))
                .thenReturn(Flux.just(above, atThreshold, below));

        StepVerifier.create(service.report(START, END, "USD", TENANT))
                .assertNext(result -> {
                    // Threshold filter is strict greater-than: a member exactly
                    // at the threshold does not clear it.
                    assertThat(result.configWarning()).isNull();
                    assertThat(result.rows()).hasSize(1);
                    assertThat(result.rows().get(0).memberNumber()).isEqualTo("M-001");
                    assertThat(result.rows().get(0).cumulativePaidReporting())
                            .isEqualByComparingTo("1200.00");
                })
                .verifyComplete();
    }

    @Test
    void report_sameCurrencySkipsRowConversionLookups() {
        TenantConfigClient.HighCostClaimantConfig cfg =
                new TenantConfigClient.HighCostClaimantConfig(new BigDecimal("500.00"), "USD");
        when(tenantConfigClient.getHighCostClaimantConfig(TENANT)).thenReturn(Mono.just(cfg));
        when(fxRateReader.convert(eq(new BigDecimal("500.00")), eq("USD"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("500.00")));
        when(fxRateReader.convert(eq(new BigDecimal("600.00")), eq("USD"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("600.00")));
        when(repository.highCostMemberTotals(START, END)).thenReturn(Flux.just(
                row("M-001", "Alice", "USD", "600.00", 1)));

        StepVerifier.create(service.report(START, END, "USD", TENANT))
                .assertNext(result -> assertThat(result.rows()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void report_emptyMemberTotals_returnsEmptyWithoutRowConversions() {
        TenantConfigClient.HighCostClaimantConfig cfg =
                new TenantConfigClient.HighCostClaimantConfig(new BigDecimal("1000.00"), "ZAR");
        when(tenantConfigClient.getHighCostClaimantConfig(TENANT)).thenReturn(Mono.just(cfg));
        when(fxRateReader.convert(eq(new BigDecimal("1000.00")), eq("ZAR"), eq("USD"), eq(END), eq(TENANT)))
                .thenReturn(Mono.just(new BigDecimal("62.50")));
        when(repository.highCostMemberTotals(START, END)).thenReturn(Flux.empty());

        StepVerifier.create(service.report(START, END, "USD", TENANT))
                .assertNext(result -> assertThat(result.rows()).isEmpty())
                .verifyComplete();
    }

    @Test
    void memberDetail_composesPagedLedger() {
        UUID memberId = UUID.randomUUID();
        ClaimsDetailResponse.ClaimLedgerRow row = new ClaimsDetailResponse.ClaimLedgerRow(
                UUID.randomUUID(), "CLM-9", "Alice A.", "Clinic A",
                Instant.now(), LocalDate.of(2026, 7, 20), Instant.now(),
                "PAID", null,
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("80.00"), "USD");

        when(repository.memberClaimLedger(memberId, START, END, 0, 50)).thenReturn(Flux.just(row));
        when(repository.memberClaimLedgerCount(memberId, START, END)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.memberDetail(memberId, START, END, 0, 50))
                .assertNext(page -> {
                    assertThat(page.content()).containsExactly(row);
                    assertThat(page.total()).isEqualTo(1L);
                    assertThat(page.page()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void memberDetail_paginatesOffsetFromPageAndSize() {
        UUID memberId = UUID.randomUUID();
        when(repository.memberClaimLedger(eq(memberId), any(), any(), anyInt(), anyInt())).thenReturn(Flux.empty());
        when(repository.memberClaimLedgerCount(memberId, START, END)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.memberDetail(memberId, START, END, 3, 25))
                .assertNext(page -> assertThat(page.page()).isEqualTo(3))
                .verifyComplete();

        verify(repository).memberClaimLedger(memberId, START, END, 75, 25);
    }

    private static HighCostClaimantRow row(String number, String name, String ccy, String paid, int claims) {
        return new HighCostClaimantRow(
                UUID.randomUUID(), number, name, ccy, new BigDecimal(paid), claims, null);
    }
}
