package com.medfund.claims.service;

import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.dto.PreAuthActivityRow;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PreAuthActivityService} (Phase 4 §A, G43). Covers
 * the per-status / per-currency rate decoration — approval share only on
 * APPROVED rows, expiry share only on EXPIRED rows, both null elsewhere —
 * and the composition of the claims-side R04/R05 side signal.
 */
@ExtendWith(MockitoExtension.class)
class PreAuthActivityServiceTest {

    @Mock private ClaimsReportQueryRepository repository;

    @InjectMocks private PreAuthActivityService service;

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 31);

    @Test
    void activity_composesPerStatusRowsWithR04R05Signal() {
        PreAuthActivityRow approved = rawRow("APPROVED", "USD", 25);
        PreAuthActivityRow expired = rawRow("EXPIRED", "USD", 5);
        PreAuthActivityRow pending = rawRow("PENDING", "USD", 70);
        PreAuthActivityResponse.R04R05SignalRow signal =
                new PreAuthActivityResponse.R04R05SignalRow(3L, 2L, new BigDecimal("400.00"));

        when(repository.preAuthActivity(START, END, null, null)).thenReturn(Flux.just(approved, expired, pending));
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(signal));

        StepVerifier.create(service.activity(START, END, null, null))
                .assertNext(response -> {
                    assertThat(response.byStatus()).hasSize(3);
                    assertThat(response.r04r05Signal().r04Count()).isEqualTo(3L);
                    assertThat(response.r04r05Signal().r05Count()).isEqualTo(2L);
                    assertThat(response.r04r05Signal().totalClaimedInR04R05())
                            .isEqualByComparingTo("400.00");
                })
                .verifyComplete();
    }

    @Test
    void activity_decoratesApprovalAndExpirySharesOnlyOnTheirStatus() {
        PreAuthActivityRow approved = rawRow("APPROVED", "USD", 25);
        PreAuthActivityRow expired = rawRow("EXPIRED", "USD", 5);
        PreAuthActivityRow pending = rawRow("PENDING", "USD", 70);

        when(repository.preAuthActivity(START, END, null, null))
                .thenReturn(Flux.just(approved, expired, pending));
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(
                new PreAuthActivityResponse.R04R05SignalRow(0L, 0L, BigDecimal.ZERO)));

        StepVerifier.create(service.activity(START, END, null, null))
                .assertNext(response -> {
                    List<PreAuthActivityRow> rows = response.byStatus();
                    PreAuthActivityRow a = rows.stream().filter(r -> r.status().equals("APPROVED")).findFirst().orElseThrow();
                    PreAuthActivityRow e = rows.stream().filter(r -> r.status().equals("EXPIRED")).findFirst().orElseThrow();
                    PreAuthActivityRow p = rows.stream().filter(r -> r.status().equals("PENDING")).findFirst().orElseThrow();

                    assertThat(a.approvalRatePct()).isEqualByComparingTo("25.00");
                    assertThat(a.expiryRatePct()).isNull();
                    assertThat(e.expiryRatePct()).isEqualByComparingTo("5.00");
                    assertThat(e.approvalRatePct()).isNull();
                    assertThat(p.approvalRatePct()).isNull();
                    assertThat(p.expiryRatePct()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void activity_sharesArePerCurrency() {
        PreAuthActivityRow approvedUsd = rawRow("APPROVED", "USD", 30);
        PreAuthActivityRow expiredUsd = rawRow("EXPIRED", "USD", 10);
        PreAuthActivityRow approvedZar = rawRow("APPROVED", "ZAR", 1);
        PreAuthActivityRow expiredZar = rawRow("EXPIRED", "ZAR", 1);

        when(repository.preAuthActivity(START, END, null, null))
                .thenReturn(Flux.just(approvedUsd, expiredUsd, approvedZar, expiredZar));
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(
                new PreAuthActivityResponse.R04R05SignalRow(0L, 0L, BigDecimal.ZERO)));

        StepVerifier.create(service.activity(START, END, null, null))
                .assertNext(response -> {
                    PreAuthActivityRow aUsd = response.byStatus().stream()
                            .filter(r -> r.status().equals("APPROVED") && r.currencyCode().equals("USD"))
                            .findFirst().orElseThrow();
                    PreAuthActivityRow eZar = response.byStatus().stream()
                            .filter(r -> r.status().equals("EXPIRED") && r.currencyCode().equals("ZAR"))
                            .findFirst().orElseThrow();

                    // USD: 30 of 40 approved; ZAR: 1 of 2 expired.
                    assertThat(aUsd.approvalRatePct()).isEqualByComparingTo("75.00");
                    assertThat(eZar.expiryRatePct()).isEqualByComparingTo("50.00");
                })
                .verifyComplete();
    }

    @Test
    void activity_emptyRows_returnsEmptyByStatusAndComposesSignal() {
        when(repository.preAuthActivity(START, END, null, null)).thenReturn(Flux.empty());
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(
                new PreAuthActivityResponse.R04R05SignalRow(1L, 0L, new BigDecimal("50.00"))));

        StepVerifier.create(service.activity(START, END, null, null))
                .assertNext(response -> {
                    assertThat(response.byStatus()).isEmpty();
                    assertThat(response.r04r05Signal().r04Count()).isEqualTo(1L);
                })
                .verifyComplete();
    }

    @Test
    void activity_forwardsStatusAndProviderFilters() {
        java.util.UUID providerId = java.util.UUID.randomUUID();
        when(repository.preAuthActivity(START, END, "APPROVED", providerId)).thenReturn(Flux.empty());
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(
                new PreAuthActivityResponse.R04R05SignalRow(0L, 0L, BigDecimal.ZERO)));

        StepVerifier.create(service.activity(START, END, "APPROVED", providerId))
                .assertNext(response -> assertThat(response.byStatus()).isEmpty())
                .verifyComplete();

        verify(repository).preAuthActivity(START, END, "APPROVED", providerId);
        verify(repository).r04r05Signal(START, END);
    }

    @Test
    void share_returnsNullWhenTotalIsZero() {
        // A single row whose count equals its own total must not divide by zero —
        // decorateRates guards with total > 0; the rate is still computed.
        PreAuthActivityRow lone = rawRow("EXPIRED", "USD", 1);
        when(repository.preAuthActivity(START, END, null, null)).thenReturn(Flux.just(lone));
        when(repository.r04r05Signal(START, END)).thenReturn(Mono.just(
                new PreAuthActivityResponse.R04R05SignalRow(0L, 0L, BigDecimal.ZERO)));

        StepVerifier.create(service.activity(START, END, null, null))
                .assertNext(response -> {
                    PreAuthActivityRow e = response.byStatus().get(0);
                    assertThat(e.expiryRatePct()).isEqualByComparingTo("100.00");
                })
                .verifyComplete();
    }

    private static PreAuthActivityRow rawRow(String status, String ccy, int count) {
        return new PreAuthActivityRow(
                status, ccy, count,
                new BigDecimal("1000.00"), new BigDecimal("800.00"),
                status.equals("PENDING") ? null : new BigDecimal("3.50"),
                null, null);
    }
}
