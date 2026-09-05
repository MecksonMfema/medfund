package com.medfund.finance.report.repository;

import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader.AmlFilingIdentity;
import com.medfund.finance.regulatory.aml.AmlSummaryRawData;
import com.medfund.finance.regulatory.aml.AmlSummaryRawDataProvider;
import com.medfund.finance.regulatory.aml.AmlThresholdReader;
import com.medfund.finance.regulatory.aml.AmlThresholds;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 17 §0.2 (S4) — the partial UNIQUE index
 * {@code ux_report_job_schedule_dedup} is the multi-instance dedup guard for
 * probe-fired runs. Two invariants:
 * <ul>
 *   <li>ad-hoc rows ({@code schedule_id IS NULL}) are outside the WHERE
 *       clause and always accepted, so a duplicate (tenant, key, period)
 *       tuple across ad-hoc + scheduled must land two rows.</li>
 *   <li>two scheduled rows with the same
 *       (tenant, key, schedule_id, period_start) must collide on the second
 *       insert with a {@link DuplicateKeyException}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "report.retention.enabled=false"
})
@Import(ReportJobScheduleDedupIT.SecurityStub.class)
class ReportJobScheduleDedupIT extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111113");

    @Autowired private DatabaseClient client;

    @BeforeEach
    void clean() {
        client.sql("DELETE FROM report_job").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        client.sql("DELETE FROM public.tenant_report_schedule").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void adhocAndScheduledRowsCoexistForSameTenantKeyPeriod() {
        UUID scheduleId = insertSchedule("COMMISSION_STATEMENT", "MONTHLY");
        LocalDate periodStart = LocalDate.of(2026, 8, 1);
        LocalDate periodEnd   = LocalDate.of(2026, 8, 31);

        Long adhoc = insertReportJob("COMMISSION_STATEMENT", "ADHOC", null, periodStart, periodEnd);
        Long scheduled = insertReportJob("COMMISSION_STATEMENT", "SCHEDULED", scheduleId, periodStart, periodEnd);

        assertThat(adhoc).isEqualTo(1L);
        assertThat(scheduled).isEqualTo(1L);
    }

    @Test
    void secondScheduledInsertForSameKeyScheduleAndPeriodRaisesDuplicate() {
        UUID scheduleId = insertSchedule("LOSS_RATIO", "MONTHLY");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd   = LocalDate.of(2026, 7, 31);

        Long first = insertReportJob("LOSS_RATIO", "SCHEDULED", scheduleId, periodStart, periodEnd);
        assertThat(first).isEqualTo(1L);

        assertThatThrownBy(() ->
                insertReportJob("LOSS_RATIO", "SCHEDULED", scheduleId, periodStart, periodEnd))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void twoAdhocRowsWithNullScheduleIdCoexistForSameKeyPeriod() {
        LocalDate periodStart = LocalDate.of(2026, 6, 1);
        LocalDate periodEnd   = LocalDate.of(2026, 6, 30);

        Long first  = insertReportJob("AGED_DEBTORS", "ADHOC", null, periodStart, periodEnd);
        Long second = insertReportJob("AGED_DEBTORS", "ADHOC", null, periodStart, periodEnd);

        assertThat(first).isEqualTo(1L);
        assertThat(second).as("ad-hoc rows (schedule_id NULL) sit outside the partial UNIQUE index")
                .isEqualTo(1L);
    }

    private UUID insertSchedule(String reportKey, String cadence) {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        client.sql("""
                        INSERT INTO public.tenant_report_schedule (
                            id, tenant_id, report_key, enabled, cadence,
                            hour_of_day, day_of_month,
                            created_by_actor_id, created_by_actor_email,
                            updated_by_actor_id, updated_by_actor_email)
                        VALUES ($1, $2, $3, TRUE, $4, 8, 1, $5, 'test@example.test', $5, 'test@example.test')
                        """)
                .bind("$1", id)
                .bind("$2", TENANT)
                .bind("$3", reportKey)
                .bind("$4", cadence)
                .bind("$5", actor)
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
        return id;
    }

    private Long insertReportJob(String reportKey, String source, UUID scheduleId,
                                 LocalDate periodStart, LocalDate periodEnd) {
        UUID jobId = UUID.randomUUID();
        var spec = client.sql("""
                        INSERT INTO report_job (
                            job_id, tenant_id, report_key, status,
                            params_json, params_hash,
                            requested_at, requested_by_email,
                            retention_class, source, schedule_id,
                            period_start, period_end)
                        VALUES ($1, $2, $3, 'requested', '{}'::jsonb, $4,
                                NOW(), 'test@example.test',
                                'OPERATIONAL_90D', $5, $6, $7, $8)
                        """)
                .bind("$1", jobId)
                .bind("$2", TENANT)
                .bind("$3", reportKey)
                .bind("$4", jobId.toString().substring(0, 32))
                .bind("$5", source)
                .bind("$7", periodStart)
                .bind("$8", periodEnd);
        spec = scheduleId == null ? spec.bindNull("$6", UUID.class) : spec.bind("$6", scheduleId);
        return spec.fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }

        /**
         * Pre-existing baseline gap: {@code StubAmlSummaryRawDataProvider}'s
         * {@code @ConditionalOnMissingBean} does not fire in this Spring
         * version's IT context, so {@code AmlSummaryReportShaper} fails to
         * autowire. Provide an explicit zero-data stub scoped to this test —
         * the dedup IT never invokes AML shape code.
         */
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
}
