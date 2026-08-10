package com.medfund.finance.service;

import com.medfund.finance.entity.MemberBalance;
import com.medfund.finance.repository.MemberBalanceRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writer path for {@code member_balances} — the finance-side snapshot of
 * the fund's obligation to each member. Mirrors {@link ProviderBalanceService}
 * both in behaviour and in the audit event shape (same four field names in
 * {@code changed[]}) so the shared audit-consumer treatment stays uniform.
 *
 * <p>Called from three places:
 * <ul>
 *   <li>{@code ClaimAdjudicatedConsumer.handleMemberPayee} — claim life-cycle
 *       events bump {@code totalClaimed} / {@code totalApproved}.</li>
 *   <li>{@code CtcPaymentService.commit / reverse} — CTC settlement bumps
 *       or decrements {@code totalPaid}.</li>
 *   <li>{@code PaymentService.markPaid} (MEMBER branch, V072 Phase 3) —
 *       cash payment bumps {@code totalPaid}.</li>
 * </ul>
 *
 * <p>Bootstrapping a new (member, currency) row on first delta is intentional
 * — the alternative (per-currency row created eagerly at member creation
 * time) creates hundreds of zero rows per tenant and complicates the
 * outstanding-balance filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberBalanceService {

    private final MemberBalanceRepository repository;
    private final AuditPublisher auditPublisher;

    @Transactional
    public Mono<MemberBalance> updateBalance(UUID memberId, String currencyCode,
                                              BigDecimal claimedDelta, BigDecimal approvedDelta,
                                              BigDecimal paidDelta,
                                              String actorId, String actorEmail) {
        return repository.findByMemberIdAndCurrencyCode(memberId, currencyCode)
            .switchIfEmpty(Mono.defer(() -> Mono.just(bootstrap(memberId, currencyCode))))
            .flatMap(balance -> {
                Map<String, Object> oldValue = Map.of(
                    "totalClaimed",       balance.getTotalClaimed().toString(),
                    "totalApproved",      balance.getTotalApproved().toString(),
                    "totalPaid",          balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );

                if (claimedDelta != null)  balance.setTotalClaimed(balance.getTotalClaimed().add(claimedDelta));
                if (approvedDelta != null) balance.setTotalApproved(balance.getTotalApproved().add(approvedDelta));
                if (paidDelta != null)     balance.setTotalPaid(balance.getTotalPaid().add(paidDelta));
                balance.setOutstandingBalance(balance.getTotalApproved().subtract(balance.getTotalPaid()));
                balance.setLastUpdatedAt(Instant.now());

                Map<String, Object> newValue = Map.of(
                    "totalClaimed",       balance.getTotalClaimed().toString(),
                    "totalApproved",      balance.getTotalApproved().toString(),
                    "totalPaid",          balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );

                return repository.save(balance)
                    .flatMap(saved -> publishAudit(saved, oldValue, newValue, actorId, actorEmail)
                        .thenReturn(saved));
            });
    }

    private MemberBalance bootstrap(UUID memberId, String currencyCode) {
        MemberBalance mb = new MemberBalance();
        mb.setMemberId(memberId);
        mb.setCurrencyCode(currencyCode);
        mb.setTotalClaimed(BigDecimal.ZERO);
        mb.setTotalApproved(BigDecimal.ZERO);
        mb.setTotalPaid(BigDecimal.ZERO);
        mb.setOutstandingBalance(BigDecimal.ZERO);
        mb.setCreatedAt(Instant.now());
        return mb;
    }

    private Mono<Void> publishAudit(MemberBalance saved, Map<String, Object> oldValue,
                                     Map<String, Object> newValue,
                                     String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Balance for member " + saved.getMemberId()
                              + " " + saved.getCurrencyCode();
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "MemberBalance",
                saved.getId().toString(),
                entityName,
                "UPDATE",
                actorId,
                actorEmail,
                oldValue,
                newValue,
                new String[]{"totalClaimed", "totalApproved", "totalPaid", "outstandingBalance"},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
