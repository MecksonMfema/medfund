package com.medfund.finance.service;

import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ProviderBalanceService {

    private static final Logger log = LoggerFactory.getLogger(ProviderBalanceService.class);

    private final ProviderBalanceRepository providerBalanceRepository;
    private final AuditPublisher auditPublisher;

    public ProviderBalanceService(ProviderBalanceRepository providerBalanceRepository,
                                  AuditPublisher auditPublisher) {
        this.providerBalanceRepository = providerBalanceRepository;
        this.auditPublisher = auditPublisher;
    }

    public Mono<ProviderBalance> findByProviderId(UUID providerId) {
        return providerBalanceRepository.findByProviderId(providerId)
            .defaultIfEmpty(createDefaultBalance(providerId));
    }

    public Mono<ProviderBalance> findByProviderIdAndCurrency(UUID providerId, String currencyCode) {
        return providerBalanceRepository.findByProviderIdAndCurrencyCode(providerId, currencyCode);
    }

    public Flux<ProviderBalance> findAllByOutstandingBalance() {
        return providerBalanceRepository.findAllByOutstandingBalanceGreaterThanZero();
    }

    @Transactional
    public Mono<ProviderBalance> updateBalance(UUID providerId, String currencyCode,
                                                BigDecimal claimedDelta, BigDecimal approvedDelta,
                                                BigDecimal paidDelta, String actorId, String actorEmail) {
        return providerBalanceRepository.findByProviderIdAndCurrencyCode(providerId, currencyCode)
            .switchIfEmpty(Mono.defer(() -> {
                var balance = new ProviderBalance();
                balance.setId(UUID.randomUUID());
                balance.setProviderId(providerId);
                balance.setCurrencyCode(currencyCode);
                balance.setTotalClaimed(BigDecimal.ZERO);
                balance.setTotalApproved(BigDecimal.ZERO);
                balance.setTotalPaid(BigDecimal.ZERO);
                balance.setOutstandingBalance(BigDecimal.ZERO);
                balance.setCreatedAt(Instant.now());
                return Mono.just(balance);
            }))
            .flatMap(balance -> {
                Map<String, Object> oldValue = Map.of(
                    "totalClaimed", balance.getTotalClaimed().toString(),
                    "totalApproved", balance.getTotalApproved().toString(),
                    "totalPaid", balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );

                if (claimedDelta != null) {
                    balance.setTotalClaimed(balance.getTotalClaimed().add(claimedDelta));
                }
                if (approvedDelta != null) {
                    balance.setTotalApproved(balance.getTotalApproved().add(approvedDelta));
                }
                if (paidDelta != null) {
                    balance.setTotalPaid(balance.getTotalPaid().add(paidDelta));
                }

                // Recalculate outstanding balance
                balance.setOutstandingBalance(balance.getTotalApproved().subtract(balance.getTotalPaid()));
                balance.setLastUpdatedAt(Instant.now());

                Map<String, Object> newValue = Map.of(
                    "totalClaimed", balance.getTotalClaimed().toString(),
                    "totalApproved", balance.getTotalApproved().toString(),
                    "totalPaid", balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );

                return providerBalanceRepository.save(balance)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "ProviderBalance", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                oldValue, newValue)
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private ProviderBalance createDefaultBalance(UUID providerId) {
        var balance = new ProviderBalance();
        balance.setProviderId(providerId);
        balance.setTotalClaimed(BigDecimal.ZERO);
        balance.setTotalApproved(BigDecimal.ZERO);
        balance.setTotalPaid(BigDecimal.ZERO);
        balance.setOutstandingBalance(BigDecimal.ZERO);
        return balance;
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"totalClaimed", "totalApproved", "totalPaid", "outstandingBalance"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
