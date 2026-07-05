package com.medfund.contributions.service;

import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.TransactionRepository;
import com.medfund.shared.audit.AuditActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Single choke point for auto-posting the fixed-cause adjustments
 * from V041: LATE_ENROLMENT_CHARGE, LATE_TERMINATION_CREDIT,
 * SCHEME_UPGRADE_ARREARS, SCHEME_DOWNGRADE_REBATE.
 *
 * <p>The three consumers ({@code MemberEnrolledConsumer},
 * {@code MemberLifecycleConsumer}, {@code SchemeChangedConsumer})
 * decide "how many months, which type" and hand that to
 * {@link #postAggregate}. This service:
 * <ul>
 *   <li>Prices one month via {@code BillingService.priceOneMember}.
 *   <li>Multiplies by {@code monthsCovered} to get the aggregate.
 *   <li>Runs an idempotency check against {@code TransactionRepository
 *       .findFirstByReferenceAndTransactionType} so replaying the Kafka
 *       event doesn't double-charge.
 *   <li>Records the transaction via {@link TransactionService#record}
 *       so the ledger + balance are updated on the same code path a
 *       manual payment uses.
 * </ul>
 *
 * <p>Fire-and-forget from the caller's perspective — errors are
 * logged; the domain event that triggered this must not fail because
 * a follow-on ledger update wobbled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LateAdjustmentService {

    private final BillingService billingService;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

    /**
     * Post one aggregate transaction covering {@code monthsCovered}
     * months of the given type. Amount = premium × months. Reference
     * carries the source key so replays are detected.
     *
     * @param memberId          member the adjustment is on behalf of (never null)
     * @param groupId           group to bill; null → bill member directly
     * @param schemeId          scheme used to compute premium (never null)
     * @param oldestMissedPeriodStart the earliest month covered — used
     *                          for pricing lookup and reference text
     * @param monthsCovered     0 → no-op; otherwise multiplied into total
     * @param currencyCode      transaction currency (must be tenant-supported)
     * @param transactionType   one of the V041 fixed-cause codes
     * @param sourceKey         idempotency key (event-source identifier)
     */
    public Mono<Void> postAggregate(UUID memberId, UUID groupId, UUID schemeId,
                                     LocalDate oldestMissedPeriodStart,
                                     int monthsCovered, String currencyCode,
                                     String transactionType, String sourceKey) {
        if (memberId == null || schemeId == null || transactionType == null || sourceKey == null) {
            log.debug("Skipping late-adjustment post: missing required field(s)");
            return Mono.empty();
        }
        if (monthsCovered <= 0) {
            log.debug("No months to adjust for source={} type={}, skipping", sourceKey, transactionType);
            return Mono.empty();
        }
        String reference = buildReference(sourceKey, monthsCovered, oldestMissedPeriodStart);
        // Idempotency guard: hasElement returns TRUE when a prior post
        // with this reference exists. Using flatMap(existing → empty())
        // + switchIfEmpty here would fire switchIfEmpty on the empty
        // Mono the flatMap produced — silently double-posting on every
        // Kafka redelivery. Locked in by LateAdjustmentServiceTest.
        return transactionRepository.findFirstByReferenceAndTransactionType(reference, transactionType)
                .hasElement()
                .flatMap(existed -> {
                    if (existed) {
                        log.info("Late-adjustment already posted for source={} type={}, skipping",
                                sourceKey, transactionType);
                        return Mono.<Void>empty();
                    }
                    return priceAndPost(memberId, groupId, schemeId, oldestMissedPeriodStart,
                            monthsCovered, currencyCode, transactionType, reference);
                })
                .onErrorResume(err -> {
                    log.warn("Late-adjustment post failed for source={} type={}: {}",
                            sourceKey, transactionType, err.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Sibling to {@link #postAggregate} for callers that already know
     * the aggregate amount (e.g. a scheme-change consumer computing
     * newPrice − oldPrice per month). Same idempotency + record path;
     * skips the internal pricing step.
     */
    public Mono<Void> postFixedAggregate(UUID memberId, UUID groupId, UUID schemeId,
                                          LocalDate periodStart, int monthsCovered,
                                          BigDecimal aggregateAmount,
                                          String currencyCode, String transactionType,
                                          String sourceKey) {
        if (memberId == null || transactionType == null || sourceKey == null) {
            log.debug("Skipping fixed-aggregate post: missing required field(s)");
            return Mono.empty();
        }
        if (monthsCovered <= 0 || aggregateAmount == null || aggregateAmount.signum() <= 0) {
            log.debug("Skipping fixed-aggregate post: months={} amount={}",
                    monthsCovered, aggregateAmount);
            return Mono.empty();
        }
        String reference = buildReference(sourceKey, monthsCovered, periodStart);
        // See postAggregate for why this uses hasElement rather than
        // flatMap+switchIfEmpty.
        return transactionRepository.findFirstByReferenceAndTransactionType(reference, transactionType)
                .hasElement()
                .flatMap(existed -> {
                    if (existed) {
                        log.info("Fixed-aggregate already posted for source={} type={}, skipping",
                                sourceKey, transactionType);
                        return Mono.<Void>empty();
                    }
                    return recordAggregate(memberId, groupId, aggregateAmount, currencyCode,
                            transactionType, reference, monthsCovered, schemeId, periodStart);
                })
                .onErrorResume(err -> {
                    log.warn("Fixed-aggregate post failed for source={} type={}: {}",
                            sourceKey, transactionType, err.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> recordAggregate(UUID memberId, UUID groupId,
                                        BigDecimal total, String currencyCode,
                                        String transactionType, String reference,
                                        int monthsCovered, UUID schemeId, LocalDate periodStart) {
        var request = new RecordTransactionRequest(
                groupId,
                groupId == null ? memberId : null,
                total,
                currencyCode,
                transactionType,
                "SYSTEM",
                reference,
                null);
        return transactionService.record(request, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL)
                .doOnNext(saved -> log.info(
                        "Auto-posted {} for member={} group={} months={} amount={} {} (txn={})",
                        transactionType, memberId, groupId, monthsCovered, total,
                        currencyCode, saved.getTransactionNumber()))
                .then();
    }

    /**
     * Dependant variant of {@link #postAggregate} (V047). Prices the
     * dependant's own premium (their age band) rather than the parent
     * member's. Ledger side still routes the charge to the parent
     * member (or their group) — dependants don't hold their own
     * running balance; the household pays.
     */
    public Mono<Void> postDependantAggregate(UUID dependantId, UUID memberId, UUID groupId,
                                              UUID schemeId,
                                              LocalDate oldestMissedPeriodStart,
                                              int monthsCovered, String currencyCode,
                                              String transactionType, String sourceKey) {
        if (dependantId == null || memberId == null || schemeId == null
                || transactionType == null || sourceKey == null) {
            log.debug("Skipping late-adjustment dependant post: missing required field(s)");
            return Mono.empty();
        }
        if (monthsCovered <= 0) {
            log.debug("No months to adjust for dependant source={} type={}, skipping",
                    sourceKey, transactionType);
            return Mono.empty();
        }
        String reference = buildReference(sourceKey, monthsCovered, oldestMissedPeriodStart);
        return transactionRepository.findFirstByReferenceAndTransactionType(reference, transactionType)
                .hasElement()
                .flatMap(existed -> {
                    if (existed) {
                        log.info("Late-adjustment (dependant) already posted for source={} type={}, skipping",
                                sourceKey, transactionType);
                        return Mono.<Void>empty();
                    }
                    return priceAndPostDependant(dependantId, memberId, groupId, schemeId,
                            oldestMissedPeriodStart, monthsCovered, currencyCode,
                            transactionType, reference);
                })
                .onErrorResume(err -> {
                    log.warn("Dependant late-adjustment post failed for source={} type={}: {}",
                            sourceKey, transactionType, err.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> priceAndPostDependant(UUID dependantId, UUID memberId, UUID groupId,
                                              UUID schemeId, LocalDate periodStart,
                                              int monthsCovered, String currencyCode,
                                              String transactionType, String reference) {
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return billingService.priceOneDependant(dependantId, memberId, schemeId, periodStart, periodEnd)
                .flatMap(unit -> {
                    BigDecimal total = unit.multiply(BigDecimal.valueOf(monthsCovered));
                    if (total.signum() <= 0) {
                        log.debug("Priced dependant amount is zero for dependant={} member={} — skipping",
                                dependantId, memberId);
                        return Mono.<Void>empty();
                    }
                    var request = new RecordTransactionRequest(
                            groupId,
                            groupId == null ? memberId : null,
                            total,
                            currencyCode,
                            transactionType,
                            "SYSTEM",
                            reference,
                            null);
                    return transactionService.record(request, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL)
                            .doOnNext(saved -> log.info(
                                    "Auto-posted {} for dependant={} member={} group={} months={} amount={} {} (txn={})",
                                    transactionType, dependantId, memberId, groupId,
                                    monthsCovered, total, currencyCode, saved.getTransactionNumber()))
                            .then();
                });
    }

    private Mono<Void> priceAndPost(UUID memberId, UUID groupId, UUID schemeId,
                                     LocalDate periodStart, int monthsCovered,
                                     String currencyCode, String transactionType,
                                     String reference) {
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        return billingService.priceOneMember(memberId, schemeId, periodStart, periodEnd)
                .flatMap(unit -> {
                    BigDecimal total = unit.multiply(BigDecimal.valueOf(monthsCovered));
                    if (total.signum() <= 0) {
                        log.debug("Priced amount is zero for member={} scheme={} — skipping",
                                memberId, schemeId);
                        return Mono.<Void>empty();
                    }
                    var request = new RecordTransactionRequest(
                            groupId,
                            groupId == null ? memberId : null,
                            total,
                            currencyCode,
                            transactionType,
                            "SYSTEM",
                            reference,
                            null);
                    return transactionService.record(request, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL)
                            .doOnNext(saved -> log.info(
                                    "Auto-posted {} for member={} group={} months={} amount={} {} (txn={})",
                                    transactionType, memberId, groupId, monthsCovered, total,
                                    currencyCode, saved.getTransactionNumber()))
                            .then();
                });
    }

    /**
     * Reference format: {@code auto:{sourceKey}:{months}m@{startYYYY-MM}}
     * — carries the idempotency key plus enough context to be legible in
     * a statement export without collapsing into an opaque hash.
     */
    private static String buildReference(String sourceKey, int monthsCovered, LocalDate periodStart) {
        String periodTag = periodStart != null
                ? String.format("%04d-%02d", periodStart.getYear(), periodStart.getMonthValue())
                : "-";
        return String.format("auto:%s:%dm@%s", sourceKey, monthsCovered, periodTag);
    }
}
