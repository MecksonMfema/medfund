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
 * <p>Three lookups beyond the item itself: provider verification status,
 * the previous payment-run date for that provider (so {@code daysSinceLastRun}
 * is meaningful), and the count of outstanding claims for the provider in the
 * current period. Each is defensive — empty results yield default values that
 * the rules can read as "not applicable" rather than failing the build.
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

        if (item.getProviderId() == null) {
            return Mono.just(new Facts(base, time));
        }

        String providerId = item.getProviderId().toString();
        return enrichVerificationStatus(base, providerId)
                .flatMap(f -> enrichPreviousRunDate(f, providerId))
                .flatMap(f -> enrichOutstandingClaims(f, providerId))
                .map(f -> new Facts(f, time));
    }

    private PaymentRunFact toFact(PaymentRunItem item, BigDecimal advancePaid) {
        PaymentRunFact f = new PaymentRunFact();
        f.setPaymentRunId(item.getPaymentRunId() != null ? item.getPaymentRunId().toString() : null);
        f.setProviderId(item.getProviderId() != null ? item.getProviderId().toString() : null);
        f.setRunDate(LocalDate.now());
        f.setAmountDue(item.getAmount());
        f.setCurrencyCode(item.getCurrencyCode());
        f.setAdvancePaid(advancePaid != null ? advancePaid : BigDecimal.ZERO);
        return f;
    }

    private Mono<PaymentRunFact> enrichVerificationStatus(PaymentRunFact f, String providerId) {
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

    private Mono<PaymentRunFact> enrichPreviousRunDate(PaymentRunFact f, String providerId) {
        return db.sql("""
                SELECT MAX(pr.executed_at) AS last_run
                FROM payment_runs pr
                JOIN payment_run_items pri ON pri.payment_run_id = pr.id
                WHERE pri.provider_id = :id AND pri.status = 'paid'
                """)
                .bind("id", java.util.UUID.fromString(providerId))
                .fetch().one()
                .map(row -> {
                    Object lastRun = row.get("last_run");
                    if (lastRun instanceof java.time.Instant inst) {
                        LocalDate prev = inst.atZone(ZoneId.systemDefault()).toLocalDate();
                        f.setPreviousRunDate(prev);
                        f.setDaysSinceLastRun((int) ChronoUnit.DAYS.between(prev, LocalDate.now()));
                    }
                    return f;
                })
                .defaultIfEmpty(f)
                .onErrorResume(err -> {
                    log.debug("[paymentrun-fact] previous-run query failed for {}: {}", providerId, err.getMessage());
                    return Mono.just(f);
                });
    }

    private Mono<PaymentRunFact> enrichOutstandingClaims(PaymentRunFact f, String providerId) {
        return db.sql("""
                SELECT COUNT(*) AS cnt
                FROM claims
                WHERE provider_id = :id
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
                    log.debug("[paymentrun-fact] outstanding-claims failed for {}: {}", providerId, err.getMessage());
                    return Mono.just(f);
                });
    }

    /** Translate post-evaluation outcomes back onto the run item. */
    public void applyOutcomes(PaymentRunItem item, PaymentRunFact fact) {
        if (fact.isScheduled()) {
            // status flip: 'pending' → 'scheduled' (or whatever the workflow uses)
            item.setStatus("scheduled");
        }
        if (fact.getWithholdAmount() != null
                && fact.getWithholdAmount().compareTo(BigDecimal.ZERO) > 0
                && item.getAmount() != null) {
            // Subtract the withheld portion from what gets paid out. The
            // separately-tracked withhold figure is on the fact.results trail.
            item.setAmount(item.getAmount().subtract(fact.getWithholdAmount()));
        }
    }

    /** Pair returned to callers. */
    public record Facts(PaymentRunFact paymentRun, TimeFact time) {}
}
