package com.medfund.claims.integration;

import com.medfund.claims.dto.ClaimsIncurredAggregateRow;
import com.medfund.claims.repository.ClaimsAggregateQueryRepository;
import com.medfund.claims.repository.ClaimsAggregateQueryRepository.Dimension;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 18 K9 IT — seeds a five-claim fixture across two currencies +
 * two lines + varying reserve trajectories and asserts the reserve-
 * balance-at-boundary DISTINCT ON semantics + subtotalIncurredExIbnr
 * arithmetic. Extends {@link AbstractClaimsReportIT} for the boot +
 * seeding infrastructure; drives the repository directly rather than
 * over HTTP to isolate the SQL.
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimsIncurredIT extends AbstractClaimsReportIT {

    @Autowired private ClaimsAggregateQueryRepository repository;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 8, 1);

    private UUID schemeGold;
    private UUID schemeSilver;
    private UUID providerId;
    private UUID groupId;
    private UUID memberHealth;
    private UUID memberLife;

    @BeforeEach
    void seedFixture() {
        schemeGold   = seedScheme("Gold");
        schemeSilver = seedScheme("Silver");
        providerId   = seedProvider("Central Clinic");
        groupId      = seedGroup("Acme");
        memberHealth = seedMember("Alice", "H", groupId, schemeGold);
        memberLife   = seedMember("Bob",   "L", groupId, schemeSilver);
    }

    @Test
    void tenantDimension_sumsPaidPlusReserveMovementPerCurrency() {
        // 3 HEALTH USD claims, each paid=100, adjudicated in-window.
        UUID c1 = seedHealthClaim("USD", "100.00");
        UUID c2 = seedHealthClaim("USD", "100.00");
        UUID c3 = seedHealthClaim("USD", "100.00");
        // 2 LIFE ZWL claims (paid on the LIFE scheme).
        UUID c4 = seedLifeClaim("ZWL", "50000.00");
        UUID c5 = seedLifeClaim("ZWL", "50000.00");

        // Reserve history — start balance = 40 (c1 only), end balance = 90 (c2 + c3):
        //   c1: reserved 40 before window start (2026-06-15), zeroed on 2026-07-05
        //   c2: reserved 30 on 2026-07-10 (inside window)
        //   c3: reserved 60 on 2026-07-20 (inside window)
        seedReserve(c1, "40", "2026-06-15T00:00:00");
        seedReserve(c1, "0",  "2026-07-05T00:00:00");
        seedReserve(c2, "30", "2026-07-10T00:00:00");
        seedReserve(c3, "60", "2026-07-20T00:00:00");
        // c4/c5: no reserve history — reserveMovement = 0.

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.TENANT, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).isNotNull();
        // USD row: paid = 300, reserveStart = 40 (c1 only), reserveEnd = 0+30+60 = 90
        // reserveMovement = 90 - 40 = 50, subtotal = 300 + 50 = 350
        ClaimsIncurredAggregateRow usd = rows.stream()
                .filter(r -> "USD".equals(r.currencyCode()))
                .findFirst().orElseThrow();
        assertThat(usd.totalPaid()).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(usd.reserveBalanceStart()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(usd.reserveBalanceEnd()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(usd.reserveMovement()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(usd.subtotalIncurredExIbnr()).isEqualByComparingTo(new BigDecimal("350"));
        assertThat(usd.claimCount()).isEqualTo(3);
        assertThat(usd.insuranceLine()).isNull();  // TENANT dimension excludes line

        // ZWL row: paid = 100000, reserve = 0
        ClaimsIncurredAggregateRow zwl = rows.stream()
                .filter(r -> "ZWL".equals(r.currencyCode()))
                .findFirst().orElseThrow();
        assertThat(zwl.totalPaid()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(zwl.reserveMovement()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zwl.claimCount()).isEqualTo(2);
    }

    @Test
    void lineDimension_splitsByInsuranceLine() {
        seedHealthClaim("USD", "200.00");
        seedLifeClaim("USD",   "150.00");

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.LINE, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).anySatisfy(r -> {
            assertThat(r.insuranceLine()).isEqualTo("HEALTH");
            assertThat(r.currencyCode()).isEqualTo("USD");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("200"));
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.insuranceLine()).isEqualTo("LIFE");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("150"));
        });
    }

    @Test
    void insuranceLineFilter_trimsToRequestedLine() {
        seedHealthClaim("USD", "200.00");
        seedLifeClaim("USD",   "150.00");

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.LINE, "LIFE", null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).allSatisfy(r -> assertThat(r.insuranceLine()).isEqualTo("LIFE"));
        assertThat(rows).anySatisfy(r -> assertThat(r.totalPaid())
                .isEqualByComparingTo(new BigDecimal("150")));
    }

    @Test
    void schemeDimension_resolvesSchemeIdAndName() {
        seedHealthClaim("USD", "100.00");   // Gold
        seedLifeClaim("USD",   "200.00");   // Silver

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.SCHEME, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).anySatisfy(r -> {
            assertThat(r.schemeId()).isEqualTo(schemeGold);
            assertThat(r.schemeName()).isEqualTo("Gold");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.schemeId()).isEqualTo(schemeSilver);
            assertThat(r.schemeName()).isEqualTo("Silver");
        });
    }

    @Test
    void schemeIdFilter_trimsToRequestedScheme() {
        seedHealthClaim("USD", "100.00");
        seedLifeClaim("USD",   "200.00");

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.SCHEME, null, schemeGold)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).allSatisfy(r -> assertThat(r.schemeId()).isEqualTo(schemeGold));
    }

    @Test
    void reserveBalance_usesLatestBeforeBoundary_notFirstRow() {
        // Regression guard on DISTINCT ON semantics: multiple reserve rows for
        // one claim before the boundary must resolve to the LATEST.
        UUID c1 = seedHealthClaim("USD", "0.00");
        seedReserve(c1, "10", "2026-06-01T00:00:00");
        seedReserve(c1, "50", "2026-06-15T00:00:00");
        seedReserve(c1, "25", "2026-06-30T00:00:00");   // latest before period_start
        seedReserve(c1, "40", "2026-07-15T00:00:00");   // latest before period_end

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.TENANT, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        ClaimsIncurredAggregateRow usd = rows.stream()
                .filter(r -> "USD".equals(r.currencyCode()))
                .findFirst().orElseThrow();
        assertThat(usd.reserveBalanceStart()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(usd.reserveBalanceEnd()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(usd.reserveMovement()).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    void outOfWindowClaims_areExcluded() {
        // Claim adjudicated BEFORE period_start must not count.
        seedClaim(memberHealth, providerId, schemeGold, "PAID", "USD",
                new BigDecimal("999.99"), new BigDecimal("999.99"), new BigDecimal("999.99"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2), LocalDate.of(2026, 6, 15));

        List<ClaimsIncurredAggregateRow> rows = repository
                .claimsIncurred(PERIOD_START, PERIOD_END, Dimension.TENANT, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(rows).isEmpty();
    }

    // ── Fixture helpers ─────────────────────────────────────────────

    private UUID seedHealthClaim(String currency, String paid) {
        return seedClaim(memberHealth, providerId, schemeGold, "PAID", currency,
                new BigDecimal(paid), new BigDecimal(paid), new BigDecimal(paid),
                LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10),
                "HEALTH");
    }

    private UUID seedLifeClaim(String currency, String paid) {
        return seedClaim(memberLife, providerId, schemeSilver, "PAID", currency,
                new BigDecimal(paid), new BigDecimal(paid), new BigDecimal(paid),
                LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10),
                "LIFE");
    }

    private void seedReserve(UUID claimId, String amount, String effectiveAtIso) {
        db.sql("""
                INSERT INTO claim_reserve_history
                    (claim_id, reserved_amount, effective_at, actor_email, reason_note)
                VALUES (:cid, :amt, :eff, 'reports-it@medfund.example', 'IT seed')
                """)
                .bind("cid", claimId)
                .bind("amt", new BigDecimal(amount))
                .bind("eff", LocalDateTime.parse(effectiveAtIso))
                .fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }
}
