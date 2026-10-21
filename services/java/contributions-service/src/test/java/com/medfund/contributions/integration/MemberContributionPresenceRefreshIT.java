package com.medfund.contributions.integration;

import com.medfund.contributions.premium.entity.EarningScheduleRun;
import com.medfund.contributions.premium.repository.EarningScheduleRunRepository;
import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.testfixtures.AbstractPolicyStatusConsumerPostgresIntegrationTest;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §C Phase 7: SQL semantics of
 * {@code EarningScheduleClosureService.refreshMemberContributionPresence}
 * against a real Postgres.
 *
 * <p>Seeds a small set of Contribution rows across a few months and
 * members, calls the refresh, and asserts the matview projects one row
 * per distinct (member_id, month) — and that a newly-inserted
 * contribution only appears after the next refresh (matview stale-until
 * refreshed).
 *
 * <p>Reuses the {@code policy-status-consumer-migration/} baseline
 * (extended in Phase 7 with {@code contributions} + the matview +
 * {@code earning_schedule_run.contrib_presence_refresh_at}) so we
 * don't stand up yet another dedicated Postgres container class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/policy-status-consumer-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(MemberContributionPresenceRefreshIT.SecurityStub.class)
class MemberContributionPresenceRefreshIT extends AbstractPolicyStatusConsumerPostgresIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    @Autowired private EarningScheduleClosureService service;
    @Autowired private EarningScheduleRunRepository runRepository;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void resetSchema() {
        db.sql("TRUNCATE contributions, earning_schedule_run CASCADE").then().block();
        // Refresh once against empty contributions so the matview is empty.
        db.sql("REFRESH MATERIALIZED VIEW member_contribution_presence")
                .then().block();
    }

    @Test
    void refresh_populatesOneRowPerDistinctMemberMonth() {
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        // Member A pays for Jan + Feb + Feb (duplicate — matview must de-dupe).
        insertContribution(memberA, LocalDate.of(2026, 1, 1));
        insertContribution(memberA, LocalDate.of(2026, 2, 1));
        insertContribution(memberA, LocalDate.of(2026, 2, 15));   // still Feb after DATE_TRUNC
        // Member B pays for Feb only.
        insertContribution(memberB, LocalDate.of(2026, 2, 1));
        // A row with NULL member_id or NULL period_start is skipped.
        insertContribution(null, LocalDate.of(2026, 3, 1));
        insertContribution(UUID.randomUUID(), null);

        service.refreshMemberContributionPresence().block();

        Long total = db.sql("SELECT COUNT(*) FROM member_contribution_presence")
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();
        assertThat(total)
                .as("A:Jan + A:Feb + B:Feb — duplicates and null-column rows must be omitted")
                .isEqualTo(3L);

        Long aFeb = db.sql("""
                SELECT COUNT(*) FROM member_contribution_presence
                 WHERE member_id = :m AND contribution_month = DATE '2026-02-01'
                """)
                .bind("m", memberA)
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();
        assertThat(aFeb).isEqualTo(1L);
    }

    @Test
    void refresh_stampsFreshnessOnNewestRunRow() {
        // Seed two runs — an older one and a newer one — so we can assert the
        // freshness stamp lands on the newest by started_at.
        EarningScheduleRun older = newRun(Instant.parse("2026-04-01T00:00:00Z"));
        EarningScheduleRun newer = newRun(Instant.parse("2026-04-02T00:00:00Z"));
        runRepository.saveAll(java.util.List.of(older, newer)).blockLast();

        service.refreshMemberContributionPresence().block();

        EarningScheduleRun refreshedOlder = runRepository.findById(older.getId()).block();
        EarningScheduleRun refreshedNewer = runRepository.findById(newer.getId()).block();
        assertThat(refreshedOlder.getContribPresenceRefreshAt())
                .as("older run's stamp must remain untouched")
                .isNull();
        assertThat(refreshedNewer.getContribPresenceRefreshAt())
                .as("newest run row must be stamped").isNotNull();
    }

    @Test
    void refresh_newContribution_visibleOnlyAfterNextRefresh() {
        UUID member = UUID.randomUUID();
        insertContribution(member, LocalDate.of(2026, 1, 1));
        service.refreshMemberContributionPresence().block();

        // Second insert without refresh — matview must not reflect it.
        insertContribution(member, LocalDate.of(2026, 2, 1));
        Long beforeRefresh = db.sql("SELECT COUNT(*) FROM member_contribution_presence WHERE member_id = :m")
                .bind("m", member)
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();
        assertThat(beforeRefresh).as("matview is stale until refreshed").isEqualTo(1L);

        service.refreshMemberContributionPresence().block();

        Long afterRefresh = db.sql("SELECT COUNT(*) FROM member_contribution_presence WHERE member_id = :m")
                .bind("m", member)
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();
        assertThat(afterRefresh).isEqualTo(2L);
    }

    private void insertContribution(UUID memberId, LocalDate periodStart) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                "INSERT INTO contributions (member_id, period_start, period_end) VALUES (:m, :s, :e)");
        spec = memberId == null ? spec.bindNull("m", UUID.class) : spec.bind("m", memberId);
        spec = periodStart == null
                ? spec.bindNull("s", LocalDate.class).bindNull("e", LocalDate.class)
                : spec.bind("s", periodStart).bind("e", periodStart.plusMonths(1).minusDays(1));
        spec.then().block();
    }

    private static EarningScheduleRun newRun(Instant startedAt) {
        EarningScheduleRun r = new EarningScheduleRun();
        r.setTenantId(UUID.randomUUID());
        r.setRunKind("SCHEDULED");
        r.setStatus("COMPLETED");
        r.setStartedAt(startedAt);
        r.setLastHeartbeatAt(startedAt);
        return r;
    }
}
