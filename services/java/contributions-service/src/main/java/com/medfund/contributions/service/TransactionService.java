package com.medfund.contributions.service;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.dto.TransactionFilterParams;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.TransactionQueryRepository;
import com.medfund.contributions.repository.TransactionRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final ContributionRepository contributionRepository;
    private final InvoiceRepository invoiceRepository;
    private final TransactionQueryRepository queryRepository;
    private final BalanceService balanceService;
    private final AuditPublisher auditPublisher;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionTypeRepository transactionTypeRepository,
                              ContributionRepository contributionRepository,
                              InvoiceRepository invoiceRepository,
                              TransactionQueryRepository queryRepository,
                              BalanceService balanceService,
                              AuditPublisher auditPublisher) {
        this.transactionRepository = transactionRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.contributionRepository = contributionRepository;
        this.invoiceRepository = invoiceRepository;
        this.queryRepository = queryRepository;
        this.balanceService = balanceService;
        this.auditPublisher = auditPublisher;
    }

    public Mono<PageResponse<Transaction>> search(TransactionFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 100);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Flux<Transaction> findByContributionId(UUID contributionId) {
        return transactionRepository.findByContributionId(contributionId);
    }

    public Flux<Transaction> findByInvoiceId(UUID invoiceId) {
        return transactionRepository.findByInvoiceId(invoiceId);
    }

    public Flux<Transaction> findAll() {
        return transactionRepository.findAllOrderByTransactionDateDesc();
    }

    @Transactional
    public Mono<Transaction> record(RecordTransactionRequest request, String actorId, String actorEmail) {
        String transactionNumber = "TXN-" + String.format("%08d", ThreadLocalRandom.current().nextInt(0, 99999999));

        var transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setTransactionNumber(transactionNumber);
        transaction.setContributionId(request.contributionId());
        transaction.setInvoiceId(request.invoiceId());
        transaction.setAmount(request.amount());
        transaction.setCurrencyCode(request.currencyCode());
        transaction.setTransactionType(request.transactionType());
        transaction.setPaymentMethod(request.paymentMethod());
        transaction.setReference(request.reference());
        transaction.setStatus("completed");
        transaction.setTransactionDate(Instant.now());
        transaction.setCreatedAt(Instant.now());
        transaction.setCreatedBy(UUID.fromString(actorId));

        return transactionRepository.save(transaction)
            .flatMap(this::applyBalanceUpdate)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Transaction", saved.getId().toString(), "CREATE", actorId, actorEmail,
                        null,
                        Map.of("transactionNumber", saved.getTransactionNumber(),
                               "status", saved.getStatus(),
                               "amount", saved.getAmount().toString(),
                               "transactionType", saved.getTransactionType(),
                               "paymentMethod", saved.getPaymentMethod() != null ? saved.getPaymentMethod() : ""))
                    .thenReturn(saved);
            }));
    }

    /**
     * Look up the transaction-type catalogue row by code, read its sign,
     * resolve the affected member/group via the linked contribution or
     * invoice, then apply the balance delta. A transaction without either
     * link is a no-op (no balance to touch).
     */
    private Mono<Transaction> applyBalanceUpdate(Transaction t) {
        if (t.getTransactionType() == null) return Mono.just(t);
        if (t.getContributionId() == null && t.getInvoiceId() == null) return Mono.just(t);

        return transactionTypeRepository.findByCode(t.getTransactionType())
                .flatMap(type -> resolveOwners(t)
                        .flatMap(owners -> balanceService.applyTransaction(t, type.getSign(), owners.memberId(), owners.groupId())
                                .thenReturn(t)))
                .defaultIfEmpty(t);
    }

    private Mono<Owners> resolveOwners(Transaction t) {
        if (t.getContributionId() != null) {
            return contributionRepository.findById(t.getContributionId())
                    .map(c -> new Owners(c.getMemberId(), c.getGroupId()))
                    .defaultIfEmpty(new Owners(null, null));
        }
        if (t.getInvoiceId() != null) {
            return invoiceRepository.findById(t.getInvoiceId())
                    .map(i -> new Owners(i.getMemberId(), i.getGroupId()))
                    .defaultIfEmpty(new Owners(null, null));
        }
        return Mono.just(new Owners(null, null));
    }

    private record Owners(UUID memberId, UUID groupId) {}

    // ---- Private helpers ----

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
            new String[]{},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
