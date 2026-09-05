package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader.AmlFilingIdentity;
import com.medfund.finance.regulatory.aml.AmlSummaryRawData;
import com.medfund.finance.regulatory.aml.AmlSummaryRawDataProvider;
import com.medfund.finance.regulatory.aml.AmlThresholdReader;
import com.medfund.finance.regulatory.aml.AmlThresholds;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers slice for the Phase 18 K7 paid-commission aggregate.
 * Seeds a fixture of commission_transaction rows across 2 currencies + 2
 * producers + status mix (PAID + ACCRUED + REVERSED); asserts only PAID
 * rows contribute and dimension GROUP BYs behave as expected.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(CommissionAggregateIT.SecurityStub.class)
@WithTenant(CommissionAggregateIT.TENANT_ID)
class CommissionAggregateIT extends AbstractIntegrationTest {

    static final String TENANT_ID = "00000000-0000-4000-8000-000000000044";

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 8, 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired private CommissionAggregateQueryRepository repository;
    @Autowired private DatabaseClient db;

    private UUID producerAce;
    private UUID producerBravo;

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }

        // Pre-existing baseline gap: StubAmlSummaryRawDataProvider's
        // @ConditionalOnMissingBean does not fire in this Spring version's IT
        // context, so AmlSummaryReportShaper fails to autowire. Provide
        // explicit stubs — matches the ReportJobScheduleDedupIT precedent.
        @Bean
        @Primary
        AmlSummaryRawDataProvider stubAmlSummaryRawDataProvider() {
            return (tenantId, periodStart, periodEnd) -> Mono.just(new AmlSummaryRawData(
                    "test-entity", "test-regulator",
                    new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                    new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                    new EnumMap<>(AmlSummaryRawData.StrStatus.class),
                    BigDecimal.ZERO));
        }

        @Bean
        @Primary
        AmlThresholdReader stubAmlThresholdReader() {
            return (tenantId, countryCode, currency, asOf) -> Mono.just(AmlThresholds.empty());
        }

        @Bean
        @Primary
        AmlFilingIdentityReader stubAmlFilingIdentityReader() {
            return tenantId -> Mono.just(AmlFilingIdentity.empty());
        }
    }

    @BeforeEach
    void seed() {
        // The shared test-migration schema is not torn down between tests —
        // wipe commission_transaction before each seed so prior runs do not
        // inflate SUM/COUNT assertions. TRUNCATE runs on the session user
        // (public_role lacks TRUNCATE), so no TenantContext here.
        db.sql("TRUNCATE commission_transaction CASCADE")
                .fetch().rowsUpdated().block(TIMEOUT);

        producerAce   = seedProducer("Ace Brokers", "USD");
        producerBravo = seedProducer("Bravo Brokers", "USD");

        // Fixture: 10 rows total.
        //   Ace HEALTH USD 50, PAID     paid_at 2026-07-05 → counted
        //   Ace HEALTH USD 50, PAID     paid_at 2026-07-15 → counted
        //   Ace HEALTH ZWL 25000, PAID  paid_at 2026-07-10 → counted
        //   Ace LIFE   USD 100, PAID    paid_at 2026-07-20 → counted
        //   Bravo HEALTH USD 30, PAID   paid_at 2026-07-25 → counted
        //   Bravo LIFE   USD 60, PAID   paid_at 2026-07-30 → counted
        //   Ace HEALTH USD 999, ACCRUED (no paid_at)       → excluded (status)
        //   Ace HEALTH USD 888, REVERSED (paid_at 2026-07-08) → excluded (status)
        //   Ace HEALTH USD 500, PAID paid_at 2026-06-30    → excluded (before window)
        //   Ace HEALTH USD 500, PAID paid_at 2026-08-01    → excluded (upper bound exclusive)
        seedTxn(producerAce,   "HEALTH", "USD", "50",    "PAID",     "2026-07-05T10:00:00");
        seedTxn(producerAce,   "HEALTH", "USD", "50",    "PAID",     "2026-07-15T10:00:00");
        seedTxn(producerAce,   "HEALTH", "ZWL", "25000", "PAID",     "2026-07-10T10:00:00");
        seedTxn(producerAce,   "LIFE",   "USD", "100",   "PAID",     "2026-07-20T10:00:00");
        seedTxn(producerBravo, "HEALTH", "USD", "30",    "PAID",     "2026-07-25T10:00:00");
        seedTxn(producerBravo, "LIFE",   "USD", "60",    "PAID",     "2026-07-30T10:00:00");

        seedTxnNoPaidAt(producerAce, "HEALTH", "USD", "999",  "ACCRUED");
        seedTxn(producerAce, "HEALTH", "USD", "888", "REVERSED", "2026-07-08T10:00:00");
        seedTxn(producerAce, "HEALTH", "USD", "500", "PAID",     "2026-06-30T10:00:00");
        seedTxn(producerAce, "HEALTH", "USD", "500", "PAID",     "2026-08-01T10:00:00");
    }

    @Test
    void tenantDimension_sumsPaidPerCurrency_excludesNonPaid() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.TENANT, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        assertThat(rows).isNotNull();
        // USD PAID in window: 50 + 50 + 100 + 30 + 60 = 290 (5 rows)
        CommissionAggregateRow usd = rows.stream()
                .filter(r -> "USD".equals(r.currencyCode()))
                .findFirst().orElseThrow();
        assertThat(usd.totalPaid()).isEqualByComparingTo(new BigDecimal("290"));
        assertThat(usd.rowCount()).isEqualTo(5);
        assertThat(usd.producerId()).isNull();
        assertThat(usd.insuranceLine()).isNull();

        // ZWL PAID in window: 25000 (1 row)
        CommissionAggregateRow zwl = rows.stream()
                .filter(r -> "ZWL".equals(r.currencyCode()))
                .findFirst().orElseThrow();
        assertThat(zwl.totalPaid()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(zwl.rowCount()).isEqualTo(1);
    }

    @Test
    void lineDimension_splitsByInsuranceLine() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.LINE, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        // HEALTH USD: 50 + 50 + 30 = 130 (3 rows); HEALTH ZWL: 25000 (1 row)
        // LIFE USD: 100 + 60 = 160 (2 rows)
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.insuranceLine()).isEqualTo("HEALTH");
            assertThat(r.currencyCode()).isEqualTo("USD");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("130"));
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.insuranceLine()).isEqualTo("LIFE");
            assertThat(r.currencyCode()).isEqualTo("USD");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("160"));
        });
    }

    @Test
    void lineFilter_trimsToRequestedLine() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.LINE, "LIFE", null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        assertThat(rows).allSatisfy(r -> assertThat(r.insuranceLine()).isEqualTo("LIFE"));
    }

    @Test
    void producerDimension_resolvesProducerIdAndName() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.PRODUCER, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        assertThat(rows).anySatisfy(r -> {
            assertThat(r.producerId()).isEqualTo(producerAce);
            assertThat(r.producerName()).isEqualTo("Ace Brokers");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.producerId()).isEqualTo(producerBravo);
            assertThat(r.producerName()).isEqualTo("Bravo Brokers");
        });
    }

    @Test
    void producerIdFilter_trimsToRequestedProducer() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.PRODUCER, null, producerAce)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        assertThat(rows).allSatisfy(r -> assertThat(r.producerId()).isEqualTo(producerAce));
    }

    @Test
    void lineAndProducerDimension_groupsAcrossBoth() {
        List<CommissionAggregateRow> rows = repository
                .aggregatePaid(PERIOD_START, PERIOD_END, AggregateDimension.LINE_AND_PRODUCER, null, null)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);

        // Ace HEALTH USD: 50 + 50 = 100 (2 rows)
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.producerId()).isEqualTo(producerAce);
            assertThat(r.insuranceLine()).isEqualTo("HEALTH");
            assertThat(r.currencyCode()).isEqualTo("USD");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("100"));
        });
        // Bravo LIFE USD: 60 (1 row)
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.producerId()).isEqualTo(producerBravo);
            assertThat(r.insuranceLine()).isEqualTo("LIFE");
            assertThat(r.totalPaid()).isEqualByComparingTo(new BigDecimal("60"));
        });
    }

    // ── Seeding helpers ─────────────────────────────────────────────

    private UUID seedProducer(String name, String homeCurrency) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO producer (id, producer_code, name, home_currency)
                VALUES (:id, :code, :name, :hc)
                """)
                .bind("id", id)
                .bind("code", "P-" + id.toString().substring(0, 8))
                .bind("name", name)
                .bind("hc", homeCurrency)
                .fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        return id;
    }

    private void seedTxn(UUID producerId, String line, String currency, String amount,
                         String status, String paidAtIso) {
        db.sql("""
                INSERT INTO commission_transaction
                    (reference, producer_id, contribution_id, member_id, insurance_line,
                     native_amount, native_currency, contribution_amount, applied_rate_pct,
                     status, paid_at, occurred_at)
                VALUES (:ref, :pid, :cid, :mid, :line, :amt, :ccy, :contrib, 10.00,
                        :status, :paid, :occ)
                """)
                .bind("ref", "R" + UUID.randomUUID().toString().substring(0, 20))
                .bind("pid", producerId)
                .bind("cid", UUID.randomUUID())
                .bind("mid", UUID.randomUUID())
                .bind("line", line)
                .bind("amt", new BigDecimal(amount))
                .bind("ccy", currency)
                .bind("contrib", new BigDecimal(amount).multiply(BigDecimal.TEN))
                .bind("status", status)
                .bind("paid", OffsetDateTime.of(LocalDateTime.parse(paidAtIso), ZoneOffset.UTC))
                .bind("occ",  OffsetDateTime.of(LocalDateTime.parse(paidAtIso), ZoneOffset.UTC))
                .fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
    }

    private void seedTxnNoPaidAt(UUID producerId, String line, String currency, String amount, String status) {
        db.sql("""
                INSERT INTO commission_transaction
                    (reference, producer_id, contribution_id, member_id, insurance_line,
                     native_amount, native_currency, contribution_amount, applied_rate_pct,
                     status, occurred_at)
                VALUES (:ref, :pid, :cid, :mid, :line, :amt, :ccy, :contrib, 10.00,
                        :status, :occ)
                """)
                .bind("ref", "R" + UUID.randomUUID().toString().substring(0, 20))
                .bind("pid", producerId)
                .bind("cid", UUID.randomUUID())
                .bind("mid", UUID.randomUUID())
                .bind("line", line)
                .bind("amt", new BigDecimal(amount))
                .bind("ccy", currency)
                .bind("contrib", new BigDecimal(amount).multiply(BigDecimal.TEN))
                .bind("status", status)
                .bind("occ", OffsetDateTime.now(ZoneOffset.UTC))
                .fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
    }
}
