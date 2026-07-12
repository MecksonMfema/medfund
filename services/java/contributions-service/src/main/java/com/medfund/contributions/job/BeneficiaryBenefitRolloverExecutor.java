package com.medfund.contributions.job;

import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * V061 — annual rollover: seed the next-year {@code beneficiary_benefits}
 * rows for every active beneficiary × active benefit combination where the
 * scheme opts into per-member ledgers and the benefit accrues over time.
 *
 * <p>Runs once per tenant on the tenant's local Jan-1 (or any cron the
 * tenant admin picks). The SQL mirrors V060's shape but for
 * {@code (year(now())+1)} and filters on {@code usage_mode}:
 * <ul>
 *   <li>{@code RUNNING_BALANCE} — always rolls over.</li>
 *   <li>{@code ONE_TIME_PER_PERIOD} — rolls over so a new period grants a
 *       fresh single use.</li>
 *   <li>{@code PER_EVENT_COUNTER} — rolls over so the event counter resets.</li>
 *   <li>{@code ONE_TIME_PER_BENEFICIARY} — <b>skipped</b>. These use
 *       {@code policy_year=0} sentinel; they pay out once per lifetime and
 *       must never reset.</li>
 *   <li>{@code NO_TRACKING} — skipped by construction (never seeded).</li>
 * </ul>
 *
 * <p>Idempotent: {@code ON CONFLICT DO NOTHING} matches V060 + the
 * enrolment seeder, so a mid-year re-run is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BeneficiaryBenefitRolloverExecutor implements JobExecutor {

    private final DatabaseClient databaseClient;

    @Override
    public JobType getJobType() { return JobType.BENEFIT_ROLLOVER; }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        int nextYear = LocalDate.now().getYear() + 1;
        log.info("BeneficiaryBenefitRollover: seeding policy_year={} for tenant {}", nextYear, tenantId);

        // Members' own benefit rows first, then dependants. Same filter set
        // as the enrolment seeder to keep the two paths consistent.
        Mono<Long> members = databaseClient.sql("""
                INSERT INTO beneficiary_benefits
                    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
                SELECT m.id, NULL, sb.id, m.scheme_id, :year, sb.currency_code
                  FROM members m
                  JOIN schemes s        ON s.id = m.scheme_id AND s.tracks_member_balances = TRUE
                  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
                 WHERE m.scheme_id IS NOT NULL
                   AND m.status IN ('active', 'enrolled')
                   AND (sb.status IS NULL OR sb.status = 'active')
                   AND sb.usage_mode IN ('RUNNING_BALANCE', 'ONE_TIME_PER_PERIOD', 'PER_EVENT_COUNTER')
                ON CONFLICT DO NOTHING
                """)
                .bind("year", nextYear)
                .fetch().rowsUpdated();

        Mono<Long> dependants = databaseClient.sql("""
                INSERT INTO beneficiary_benefits
                    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
                SELECT d.member_id, d.id, sb.id, m.scheme_id, :year, sb.currency_code
                  FROM dependants d
                  JOIN members m        ON m.id = d.member_id
                  JOIN schemes s        ON s.id = m.scheme_id AND s.tracks_member_balances = TRUE
                  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
                 WHERE m.scheme_id IS NOT NULL
                   AND (d.status IS NULL OR d.status IN ('active', 'enrolled'))
                   AND (sb.status IS NULL OR sb.status = 'active')
                   AND sb.usage_mode IN ('RUNNING_BALANCE', 'ONE_TIME_PER_PERIOD', 'PER_EVENT_COUNTER')
                ON CONFLICT DO NOTHING
                """)
                .bind("year", nextYear)
                .fetch().rowsUpdated();

        return members.zipWith(dependants)
                .doOnNext(counts -> log.info("BeneficiaryBenefitRollover tenant {}: members={} dependants={} rows inserted",
                        tenantId, counts.getT1(), counts.getT2()))
                .then();
    }
}
