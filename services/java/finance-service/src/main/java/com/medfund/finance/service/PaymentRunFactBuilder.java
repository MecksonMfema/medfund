package com.medfund.finance.service;

import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.rules.fact.PaymentRunFact;
import com.medfund.rules.fact.TimeFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Translates a {@link PaymentRunItem} candidate into a {@link PaymentRunFact}
 * (and a companion {@link TimeFact}) for rules-engine evaluation.
 *
 * <p>Enrichment dispatches on {@code payee_type}:
 * <ul>
 *   <li><b>PROVIDER</b> — verification status from {@code providers}, previous
 *       provider payout, outstanding provider claims.</li>
 *   <li><b>MEMBER</b> — verification status from {@code members}, previous
 *       member payout, outstanding member-payee claims.</li>
 * </ul>
 * Empty results yield default values so rules see "not applicable" rather
 * than a failed build.
 */
@Slf4j
@Component
public class PaymentRunFactBuilder {

    private final DatabaseClient db;

    public PaymentRunFactBuilder(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Facts> build(PaymentRunItem item, BigDecimal advancePaid) {
        PaymentRunFact base = toFact(item, advancePaid);
        TimeFact time = TimeFact.of(LocalDate.now());

        if ("MEMBER".equalsIgnoreCase(item.getPayeeType())) {
            if (item.getMemberId() == null) {
                return Mono.just(new Facts(base, time));
            }
            String memberId = item.getMemberId().toString();
            return enrichVerificationStatusMember(base, memberId)
                    .flatMap(f -> enrichPreviousRunDateMember(f, memberId))
                    .flatMap(f -> enrichOutstandingClaimsMember(f, memberId))
                    .map(f -> new Facts(f, time));
        }

        if (item.getProviderId() == null) {
            return Mono.just(new Facts(base, time));
        }
        String providerId = item.getProviderId().toString();
        return enrichVerificationStatusProvider(base, providerId)
                .flatMap(f -> enrichPreviousRunDateProvider(f, providerId))
                .flatMap(f -> enrichOutstandingClaimsProvider(f, providerId))
                .map(f -> new Facts(f, time));
    }

    private PaymentRunFact toFact(PaymentRunItem item, BigDecimal advancePaid) {
        PaymentRunFact f = new PaymentRunFact();
        f.setPaymentRunId(item.getPaymentRunId() != null ? item.getPaymentRunId().toString() : null);
        f.setProviderId(item.getProviderId() != null ? item.getProviderId().toString() : null);
        f.setMemberId(item.getMemberId() != null ? item.getMemberId().toString() : null);
        f.setPayeeType(item.getPayeeType() != null ? item.getPayeeType() : "PROVIDER");
        f.setRunDate(LocalDate.now());
        f.setAmountDue(item.getAmount());
        f.setCurrencyCode(item.getCurrencyCode());
        f.setAdvancePaid(advancePaid != null ? advancePaid : BigDecimal.ZERO);
        return f;
    }

    // ---- Provider branch --------------------------------------------------

    private Mono<PaymentRunFact> enrichVerificationStatusProvider(PaymentRunFact f, String providerId) {
        return db.sql("SELECT status FROM public.providers WHERE id = :id")
                .bind("id", java.util.UUID.fromString(providerId))
                .fetch().one()
                .map(row -> {
                    String status = row.get("status") == null ? "" : row.get("status").toString();
                    f.setProviderVerified("active".equalsIgnoreCase(status) || "verified".equalsIgnoreCase(status));
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] provider lookup failed for {}: {}", providerId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<PaymentRunFact> enrichPreviousRunDateProvider(PaymentRunFact f, String providerId) {
        return db.sql("""
                SELECT MAX(pr.executed_at) AS last_run
                FROM payment_runs pr
                JOIN payment_run_items pri ON pri.payment_run_id = pr.id
                WHERE pri.provider_id = :id AND pri.status = 'paid'
                """)
                .bind("id", java.util.UUID.fromString(providerId))
                .fetch().one()
                .map(row -> applyPreviousRun(f, row.get("last_run")))
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] previous-run query failed for provider {}: {}", providerId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<PaymentRunFact> enrichOutstandingClaimsProvider(PaymentRunFact f, String providerId) {
        return db.sql("""
                SELECT COUNT(*) AS cnt
                FROM claims
                WHERE provider_id = :id
                  AND payee_type = 'PROVIDER'
                  AND status IN ('ADJUDICATED', 'COMMITTED')
                """)
                .bind("id", java.util.UUID.fromString(providerId))
                .fetch().one()
                .map(row -> {
                    if (row.get("cnt") instanceof Number n) f.setOutstandingClaimsCount(n.intValue());
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] outstanding-claims failed for provider {}: {}", providerId, err.getMessage());
                    return Mono.just(f);
                });
    }

    // ---- Member branch ----------------------------------------------------

    private Mono<PaymentRunFact> enrichVerificationStatusMember(PaymentRunFact f, String memberId) {
        return db.sql("SELECT status FROM members WHERE id = :id")
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    String status = row.get("status") == null ? "" : row.get("status").toString();
                    f.setProviderVerified("active".equalsIgnoreCase(status) || "verified".equalsIgnoreCase(status));
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] member lookup failed for {}: {}", memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<PaymentRunFact> enrichPreviousRunDateMember(PaymentRunFact f, String memberId) {
        return db.sql("""
                SELECT MAX(pr.executed_at) AS last_run
                FROM payment_runs pr
                JOIN payment_run_items pri ON pri.payment_run_id = pr.id
                WHERE pri.member_id = :id AND pri.status = 'paid'
                """)
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> applyPreviousRun(f, row.get("last_run")))
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] previous-run query failed for member {}: {}", memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<PaymentRunFact> enrichOutstandingClaimsMember(PaymentRunFact f, String memberId) {
        return db.sql("""
                SELECT COUNT(*) AS cnt
                FROM claims
                WHERE member_id = :id
                  AND payee_type = 'MEMBER'
                  AND status IN ('ADJUDICATED', 'COMMITTED')
                """)
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    if (row.get("cnt") instanceof Number n) f.setOutstandingClaimsCount(n.intValue());
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] outstanding-claims failed for member {}: {}", memberId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private PaymentRunFact applyPreviousRun(PaymentRunFact f, Object lastRun) {
        if (lastRun instanceof java.time.Instant inst) {
            LocalDate prev = inst.atZone(ZoneId.systemDefault()).toLocalDate();
            f.setPreviousRunDate(prev);
            f.setDaysSinceLastRun((int) ChronoUnit.DAYS.between(prev, LocalDate.now()));
        }
        return f;
    }

    /** Translate post-evaluation outcomes back onto the run item. */
    public void applyOutcomes(PaymentRunItem item, PaymentRunFact fact) {
        if (fact.isScheduled()) {
            item.setStatus("scheduled");
        }
        if (fact.getWithholdAmount() != null
                && fact.getWithholdAmount().compareTo(BigDecimal.ZERO) > 0
                && item.getAmount() != null) {
            item.setAmount(item.getAmount().subtract(fact.getWithholdAmount()));
        }
    }

    /** Pair returned to callers. */
    public record Facts(PaymentRunFact paymentRun, TimeFact time) {}
}
