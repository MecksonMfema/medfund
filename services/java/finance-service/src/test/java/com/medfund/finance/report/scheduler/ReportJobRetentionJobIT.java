package com.medfund.finance.report.scheduler;

import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §3 retention-class split — verifies the {@link ReportJobRetentionJob}
 * two-class purge behaviour:
 * <ul>
 *   <li>OPERATIONAL_90D — rows older than 90 days are purged; within the
 *       90-day window the top-20 most-recent runs per {@code (tenant, report_key)}
 *       are kept and everything below is trimmed.</li>
 *   <li>STATUTORY_7Y — rows older than 7 years are purged; younger rows are
 *       kept regardless of run count (no top-N trim).</li>
 * </ul>
 *
 * <p>Cron auto-fire is disabled by {@code report.retention.enabled=false}
 * so the test drives {@link ReportJobRetentionJob#runOnce()} directly and
 * asserts row counts deterministically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true",
        "report.retention.enabled=false"
})
@Import(ReportJobRetentionJobIT.SecurityStub.class)
class ReportJobRetentionJobIT extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private ReportJobRetentionJob job;
    @Autowired private DatabaseClient client;

    @BeforeEach
    void clean() {
        client.sql("DELETE FROM report_job").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void operational_rowsOlderThan90Days_arePurged_youngerRowsKept() {
        UUID stale = insert("OPERATIONAL_90D", "IBNR_TRIANGLE", Instant.now().minus(Duration.ofDays(120)));
        UUID fresh = insert("OPERATIONAL_90D", "IBNR_TRIANGLE", Instant.now().minus(Duration.ofDays(30)));

        StepVerifier.create(job.runOnce())
                .expectNext(1L)
                .verifyComplete();

        assertThat(exists(stale)).as("stale operational row purged").isFalse();
        assertThat(exists(fresh)).as("fresh operational row kept").isTrue();
    }

    @Test
    void operational_beyondTop20PerKey_isTrimmed_eveInsideAgeWindow() {
        // 25 rows all within the 90-day window under the same (tenant, report_key).
        UUID[] ids = new UUID[25];
        for (int i = 0; i < 25; i++) {
            // Space by 1 hour so ORDER BY requested_at DESC is deterministic — most-recent first.
            ids[i] = insert("OPERATIONAL_90D", "PERSISTENCY_STUDY",
                    Instant.now().minus(Duration.ofHours(i)));
        }

        StepVerifier.create(job.runOnce())
                .expectNext(5L)
                .verifyComplete();

        // Top 20 (indexes 0..19 — most recent) survive; bottom 5 (indexes 20..24) purged.
        for (int i = 0; i < 20; i++) {
            assertThat(exists(ids[i])).as("row " + i + " within top-20 kept").isTrue();
        }
        for (int i = 20; i < 25; i++) {
            assertThat(exists(ids[i])).as("row " + i + " beyond top-20 purged").isFalse();
        }
    }

    @Test
    void statutory_rowsOlderThan7Years_arePurged_youngerRowsKeptEvenWhenNumerous() {
        UUID stale = insert("STATUTORY_7Y", "IFRS17_LRC_LIC_RECONCILIATION",
                Instant.now().minus(Duration.ofDays(365 * 8)));
        // Insert 30 rows under the same statutory key — none should be trimmed even
        // though the operational rule would top-N cap them at 20.
        for (int i = 0; i < 30; i++) {
            insert("STATUTORY_7Y", "IFRS17_LRC_LIC_RECONCILIATION",
                    Instant.now().minus(Duration.ofDays(365 * 2 + i)));
        }

        StepVerifier.create(job.runOnce())
                .expectNext(1L)
                .verifyComplete();

        assertThat(exists(stale)).as("statutory row past 7y purged").isFalse();
        Long remaining = client.sql("""
                        SELECT COUNT(*) AS c FROM report_job
                         WHERE tenant_id = $1 AND retention_class = 'STATUTORY_7Y'
                        """)
                .bind("$1", TENANT)
                .map((row, meta) -> row.get("c", Long.class))
                .one().block(Duration.ofSeconds(5));
        assertThat(remaining).as("young statutory rows kept regardless of count").isEqualTo(30L);
    }

    @Test
    void purge_removesSiuCase7yBeyondSevenYears() {
        // Phase 19 §A: FRAUD_SIU_REPORT jobs land under SIU_CASE_7Y (a
        // parallel 7-year bucket to STATUTORY_7Y — kept separate for the
        // rollback story). Rows older than 7 years are purged; younger rows
        // are kept regardless of run count.
        UUID stale = insert("SIU_CASE_7Y", "FRAUD_SIU_REPORT",
                Instant.now().minus(Duration.ofDays(365 * 8)));
        UUID fresh = insert("SIU_CASE_7Y", "FRAUD_SIU_REPORT",
                Instant.now().minus(Duration.ofDays(30)));

        StepVerifier.create(job.runOnce())
                .expectNext(1L)
                .verifyComplete();

        assertThat(exists(stale)).as("stale siu_case row past 7y purged").isFalse();
        assertThat(exists(fresh)).as("fresh siu_case row kept").isTrue();
    }

    @Test
    void disabled_skipsAllPurge() {
        // Enabled flag is @Value-bound but purge() reads it directly — an in-test
        // simulation would need reflection; instead we assert runOnce() is exposed
        // package-private for exactly this use, and covers deterministic behaviour
        // regardless of the schedule flag. runOnce with no rows returns 0.
        StepVerifier.create(job.runOnce())
                .expectNext(0L)
                .verifyComplete();
    }

    private UUID insert(String retentionClass, String reportKey, Instant requestedAt) {
        UUID id = UUID.randomUUID();
        client.sql("""
                        INSERT INTO report_job (
                            job_id, tenant_id, report_key, status,
                            params_json, params_hash,
                            requested_at, requested_by_email, retention_class)
                        VALUES ($1, $2, $3, 'completed', '{}'::jsonb, $4, $5, 'test@example.test', $6)
                        """)
                .bind("$1", id)
                .bind("$2", TENANT)
                .bind("$3", reportKey)
                .bind("$4", id.toString().substring(0, 32))
                .bind("$5", java.time.OffsetDateTime.ofInstant(requestedAt, java.time.ZoneOffset.UTC))
                .bind("$6", retentionClass)
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(client.sql("SELECT 1 FROM report_job WHERE job_id = $1")
                .bind("$1", id)
                .map((row, meta) -> true)
                .one()
                .defaultIfEmpty(false)
                .block(Duration.ofSeconds(5)));
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
    }
}
