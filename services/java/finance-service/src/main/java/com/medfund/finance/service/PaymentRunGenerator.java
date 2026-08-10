package com.medfund.finance.service;

import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto-populates a freshly created draft {@link PaymentRun} with one
 * {@link PaymentRunItem} + {@link Payment} pair per eligible payee.
 *
 * <p>Two sources feed the run:
 *   <ul>
 *     <li><b>PROVIDER</b> — every row in {@code provider_balances} for
 *         the run's currency with a positive outstanding balance.</li>
 *     <li><b>MEMBER</b>   — every member with a positive net-payable
 *         balance in the run's currency (aggregated from
 *         {@code member_payables} less already-consumed
 *         {@code member_payable_applications}).</li>
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
    private final MemberPayableBalanceRepository memberPayableBalanceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final AuditPublisher auditPublisher;

    public Mono<Integer> populate(PaymentRun run) {
        String currency = run.getCurrencyCode();
        UUID runId = run.getId();
        return Flux.merge(
                    populateProviderItems(runId, currency),
                    populateMemberItems(runId, currency))
                .count()
                .map(Long::intValue);
    }

    private Flux<PaymentRunItem> populateProviderItems(UUID runId, String currency) {
        return providerBalanceRepository.findOutstandingByCurrency(currency)
                .filter(bal -> bal.getOutstandingBalance() != null
                        && bal.getOutstandingBalance().signum() > 0)
                .flatMap(bal -> createPaymentAndItem(
                        runId, currency, "PROVIDER",
                        bal.getProviderId(), null, bal.getOutstandingBalance()));
    }

    private Flux<PaymentRunItem> populateMemberItems(UUID runId, String currency) {
        return memberPayableBalanceRepository.findOutstandingByCurrency(currency)
                .filter(bal -> bal.outstanding() != null && bal.outstanding().signum() > 0)
                .flatMap(bal -> createPaymentAndItem(
                        runId, currency, "MEMBER",
                        null, bal.memberId(), bal.outstanding()));
    }

    private Mono<PaymentRunItem> createPaymentAndItem(UUID runId, String currency,
                                                     String payeeType,
                                                     UUID providerId, UUID memberId,
                                                     BigDecimal amount) {
        return generatePaymentNumber()
                .flatMap(paymentNumber -> {
                    var payment = new Payment();
                    payment.setPaymentNumber(paymentNumber);
                    payment.setProviderId(providerId);
                    payment.setMemberId(memberId);
                    payment.setPayeeType(payeeType);
                    payment.setAmount(amount);
                    payment.setCurrencyCode(currency);
                    payment.setPaymentType("claim_payment");
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
                    item.setPayeeType(payeeType);
                    item.setAmount(amount);
                    item.setCurrencyCode(currency);
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
