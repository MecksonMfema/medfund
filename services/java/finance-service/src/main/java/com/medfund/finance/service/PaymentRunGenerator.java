package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.repository.ProducerCommissionSummary;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.repository.MemberBalanceRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto-populates a freshly created draft {@link PaymentRun} with one
 * {@link PaymentRunItem} + {@link Payment} pair per eligible payee.
 *
 * <p>Three sources feed the run:
 *   <ul>
 *     <li><b>PROVIDER</b> — every row in {@code provider_balances} for
 *         the run's currency with a positive outstanding balance.</li>
 *     <li><b>MEMBER</b>   — every member with a positive net-payable
 *         balance in the run's currency (aggregated from
 *         {@code member_payables} less already-consumed
 *         {@code member_payable_applications}).</li>
 *     <li><b>PRODUCER</b> — every producer with ACCRUED commissions in
 *         the requested {@code [periodStart, periodEnd]} whose
 *         {@code home_currency} matches the run currency (Phase 11 §A).
 *         Native amounts convert to the producer's home currency at
 *         commit-time FX per {@code .claude/multi-currency.md:164};
 *         withholding tax is deducted per
 *         {@code producer.wht_pct_override}.</li>
 *   </ul>
 *
 * <p>Runs inside {@code PaymentRunService.create()}'s transaction — if
 * this generator errors, the run creation rolls back cleanly and no
 * orphan header remains.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRunGenerator {

    private final ProviderBalanceRepository providerBalanceRepository;
    private final MemberBalanceRepository memberBalanceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final CommissionTransactionRepository commissionTransactionRepository;
    private final ProducerRepository producerRepository;
    private final FxConverter fxConverter;
    private final AuditPublisher auditPublisher;

    /** Kept for callers that never need the producer branch (PROVIDER/MEMBER runs). */
    public Mono<Integer> populate(PaymentRun run) {
        return populate(run, null, null);
    }

    /**
     * Route on {@code run.payeeType}. Only the PRODUCER branch reads
     * {@code periodStart} / {@code periodEnd}; PROVIDER + MEMBER drain
     * outstanding balances irrespective of period.
     */
    public Mono<Integer> populate(PaymentRun run, LocalDate periodStart, LocalDate periodEnd) {
        String currency = run.getCurrencyCode();
        UUID runId = run.getId();
        String payeeType = run.getPayeeType() == null ? "PROVIDER" : run.getPayeeType().toUpperCase();
        return switch (payeeType) {
            case "PROVIDER" -> populateProviderItems(runId, currency).count().map(Long::intValue);
            case "MEMBER"   -> populateMemberItems(runId, currency).count().map(Long::intValue);
            case "PRODUCER" -> {
                if (periodStart == null || periodEnd == null) {
                    yield Mono.error(new IllegalArgumentException(
                            "PRODUCER payment run requires periodStart and periodEnd"));
                }
                yield populateProducerItems(runId, currency, periodStart, periodEnd)
                        .count().map(Long::intValue);
            }
            default -> Mono.error(new IllegalStateException(
                    "Unknown payeeType on payment run: " + payeeType));
        };
    }

    Flux<PaymentRunItem> populateProviderItems(UUID runId, String currency) {
        return providerBalanceRepository.findOutstandingByCurrency(currency)
                .filter(bal -> bal.getOutstandingBalance() != null
                        && bal.getOutstandingBalance().signum() > 0)
                .flatMap(bal -> createPaymentAndItem(
                        runId, currency, "PROVIDER",
                        bal.getProviderId(), null, null,
                        bal.getOutstandingBalance(), null));
    }

    Flux<PaymentRunItem> populateMemberItems(UUID runId, String currency) {
        return memberBalanceRepository.findOutstandingByCurrency(currency)
                .filter(bal -> bal.getOutstandingBalance() != null
                        && bal.getOutstandingBalance().signum() > 0)
                .flatMap(bal -> createPaymentAndItem(
                        runId, currency, "MEMBER",
                        null, bal.getMemberId(), null,
                        bal.getOutstandingBalance(), null));
    }

    /**
     * Aggregate ACCRUED commission_transaction rows for producers whose
     * {@code home_currency} matches the run currency, group by producer,
     * FX-convert each native leg to the producer's home currency at commit
     * time, deduct WHT if the producer has an override, and emit one
     * PaymentRunItem per producer.
     *
     * <p>Homogeneous by (home_currency, period) — the aggregate query
     * filters by home_currency so the resulting items land in one run.
     * Producers with zero accruals in the window are skipped implicitly
     * (they don't show up in the GROUP BY result).
     */
    Flux<PaymentRunItem> populateProducerItems(UUID runId, String currency,
                                               LocalDate periodStart, LocalDate periodEnd) {
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return Flux.deferContextual(ctx -> {
            UUID tenantIdRef = parseTenant(TenantContext.get(ctx));
            return commissionTransactionRepository.aggregateForPayout(currency, from, to)
                    .groupBy(ProducerCommissionSummary::producerId)
                    .flatMap(byProducer -> byProducer.collectList()
                            .flatMap(summaries -> {
                                UUID producerId = byProducer.key();
                                return producerRepository.findById(producerId)
                                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                                "Producer not found for commission aggregation: " + producerId)))
                                        .flatMap(producer -> sumInHomeCurrency(
                                                    summaries, producer.getHomeCurrency(),
                                                    periodStart, tenantIdRef)
                                                .flatMap(homeAmount -> {
                                                    BigDecimal netAmount = applyWht(homeAmount, producer);
                                                    return createPaymentAndItem(runId, currency, "PRODUCER",
                                                            null, null, producerId,
                                                            netAmount, producer.getWhtPctOverride());
                                                }));
                            }));
        });
    }

    private Mono<BigDecimal> sumInHomeCurrency(List<ProducerCommissionSummary> summaries,
                                               String homeCurrency, LocalDate asOf, UUID tenantId) {
        return Flux.fromIterable(summaries)
                .flatMap(s -> {
                    if (s.nativeCurrency() != null && s.nativeCurrency().equals(homeCurrency)) {
                        return Mono.just(s.totalNative());
                    }
                    return fxConverter.convert(s.totalNative(), s.nativeCurrency(),
                                    homeCurrency, asOf, tenantId);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal applyWht(BigDecimal gross, Producer producer) {
        if (producer.getWhtPctOverride() == null) return gross;
        BigDecimal wht = gross.multiply(producer.getWhtPctOverride()).movePointLeft(2);
        return gross.subtract(wht);
    }

    private Mono<PaymentRunItem> createPaymentAndItem(UUID runId, String currency,
                                                     String payeeType,
                                                     UUID providerId, UUID memberId, UUID producerId,
                                                     BigDecimal amount, BigDecimal whtPct) {
        return generatePaymentNumber()
                .flatMap(paymentNumber -> {
                    var payment = new Payment();
                    payment.setPaymentNumber(paymentNumber);
                    payment.setProviderId(providerId);
                    payment.setMemberId(memberId);
                    payment.setProducerId(producerId);
                    payment.setPayeeType(payeeType);
                    payment.setAmount(amount);
                    payment.setCurrencyCode(currency);
                    payment.setPaymentType("PRODUCER".equals(payeeType)
                            ? "producer_commission" : "claim_payment");
                    payment.setStatus("pending");
                    payment.setCreatedAt(Instant.now());
                    return paymentRepository.save(payment);
                })
                .flatMap(saved -> {
                    var item = new PaymentRunItem();
                    item.setPaymentRunId(runId);
                    item.setPaymentId(saved.getId());
                    item.setProviderId(providerId);
                    item.setMemberId(memberId);
                    item.setProducerId(producerId);
                    item.setPayeeType(payeeType);
                    item.setAmount(amount);
                    item.setCurrencyCode(currency);
                    item.setWithholdingTaxPct(whtPct);
                    item.setStatus("pending");
                    return paymentRunItemRepository.save(item);
                })
                .flatMap(item -> auditItemCreated(runId, item).thenReturn(item));
    }

    private Mono<String> generatePaymentNumber() {
        String number = "PAY-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return paymentRepository.existsByPaymentNumber(number)
                .flatMap(exists -> exists ? generatePaymentNumber() : Mono.just(number));
    }

    private static UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    private Mono<Void> auditItemCreated(UUID runId, PaymentRunItem item) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "PaymentRunItem",
                    item.getId().toString(),
                    "Item for run " + runId,
                    "CREATE",
                    AuditActor.SYSTEM_ID,
                    AuditActor.SYSTEM_EMAIL,
                    null,
                    Map.of(
                            "payeeType", item.getPayeeType(),
                            "amount", item.getAmount().toPlainString(),
                            "currency", item.getCurrencyCode()),
                    new String[]{},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}
