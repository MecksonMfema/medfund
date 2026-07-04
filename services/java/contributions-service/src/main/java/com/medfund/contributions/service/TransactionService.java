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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;
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
    private final ContributionEventPublisher eventPublisher;
    private final DatabaseClient db;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionTypeRepository transactionTypeRepository,
                              ContributionRepository contributionRepository,
                              InvoiceRepository invoiceRepository,
                              TransactionQueryRepository queryRepository,
                              BalanceService balanceService,
                              AuditPublisher auditPublisher,
                              ContributionEventPublisher eventPublisher,
                              DatabaseClient db) {
        this.transactionRepository = transactionRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.contributionRepository = contributionRepository;
        this.invoiceRepository = invoiceRepository;
        this.queryRepository = queryRepository;
        this.balanceService = balanceService;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.db = db;
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
        // Owner is required — the ledger has to know whose balance to
        // move. Enforced here (not on the DTO) so the caller gets a
        // useful 422 that names the alternatives.
        if (request.groupId() == null && request.memberId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Provide either a groupId or a memberId — the transaction must anchor to an owner"));
        }
        if (request.groupId() != null && request.memberId() != null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Only one of groupId or memberId may be set — the DB CHECK enforces exclusivity"));
        }
        // Adjustments must justify themselves — CREDIT/DEBIT bypass the
        // usual payment/refund proof (a bank reference, a receipt) so we
        // require a written reason instead. Payment / refund / etc pass
        // through without needing one.
        if (requiresReason(request.transactionType()) &&
                (request.reason() == null || request.reason().isBlank())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "A reason is required for " + request.transactionType().toUpperCase() +
                " adjustments — record why the ledger is being moved"));
        }

        String transactionNumber = "TXN-" + String.format("%08d", ThreadLocalRandom.current().nextInt(0, 99999999));

        var transaction = new Transaction();
        transaction.setTransactionNumber(transactionNumber);
        transaction.setGroupId(request.groupId());
        transaction.setMemberId(request.memberId());
        transaction.setAmount(request.amount());
        transaction.setCurrencyCode(request.currencyCode());
        transaction.setTransactionType(request.transactionType());
        transaction.setPaymentMethod(request.paymentMethod());
        transaction.setReference(request.reference());
        transaction.setReason(request.reason());
        transaction.setStatus("completed");
        transaction.setTransactionDate(Instant.now());
        transaction.setCreatedAt(Instant.now());
        transaction.setCreatedBy(UUID.fromString(actorId));

        return transactionRepository.save(transaction)
            .flatMap(this::applyBalanceUpdate)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Transaction", saved.getId().toString(), saved.getTransactionNumber(),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("transactionNumber", saved.getTransactionNumber(),
                               "status", saved.getStatus(),
                               "amount", saved.getAmount().toString(),
                               "transactionType", saved.getTransactionType(),
                               "paymentMethod", saved.getPaymentMethod() != null ? saved.getPaymentMethod() : ""))
                    // Fan out a receipt event so notification-service can
                    // email the group liaison or the individual member.
                    // Fire-and-forget — email failure never rolls back the
                    // recorded transaction. Only ORDINARY contributions
                    // produce a receipt; ADJUSTMENT / DEBIT / CREDIT /
                    // REVERSAL are internal ledger operations that don't
                    // warrant an outbound email.
                    .then(isReceiptEligible(saved.getTransactionType())
                        ? resolveRecipientName(saved.getGroupId(), saved.getMemberId())
                            .flatMap(recipientName -> eventPublisher.publishTransactionRecorded(
                                new ContributionEventPublisher.TransactionRecordedPayload(
                                        saved.getId().toString(),
                                        saved.getTransactionNumber(),
                                        tenantId,
                                        saved.getGroupId()  != null ? saved.getGroupId().toString()  : null,
                                        saved.getMemberId() != null ? saved.getMemberId().toString() : null,
                                        moneyDisplay(saved.getAmount()),
                                        saved.getCurrencyCode(),
                                        saved.getTransactionType(),
                                        saved.getPaymentMethod(),
                                        saved.getReference(),
                                        saved.getTransactionDate() != null
                                                ? saved.getTransactionDate().toString() : null,
                                        recipientName.isBlank() ? null : recipientName)))
                        : Mono.<Void>empty())
                    .thenReturn(saved);
            }));
    }

    /**
     * Resolve the friendly display name for the payer so the receipt PDF
     * shows "Acme Corp" or "Jane Doe" instead of a truncated UUID. Same
     * shape as BillingService.resolveRecipientName so the two receipts
     * stay consistent. Falls back to the empty string on any error — the
     * renderer will use its own fallback in that case.
     */
    private Mono<String> resolveRecipientName(UUID groupId, UUID memberId) {
        if (groupId != null) {
            return db.sql("SELECT name FROM groups WHERE id = :id")
                    .bind("id", groupId)
                    .map(row -> row.get("name", String.class))
                    .one()
                    .onErrorReturn("")
                    .defaultIfEmpty("");
        }
        if (memberId != null) {
            return db.sql("SELECT (first_name || ' ' || last_name) AS name FROM members WHERE id = :id")
                    .bind("id", memberId)
                    .map(row -> row.get("name", String.class))
                    .one()
                    .onErrorReturn("")
                    .defaultIfEmpty("");
        }
        return Mono.just("");
    }

    /**
     * True when this transaction type warrants an outbound receipt email
     * to the payer. Only actual payments received (PAYMENT) do —
     * refunds, charges, write-offs, adjustments, and payment reversals
     * are internal ledger operations that the payer either already
     * knows about (out-of-band operator communication) or would find
     * confusing to receive an unsolicited "receipt" for.
     */
    private static boolean isReceiptEligible(String transactionType) {
        return "PAYMENT".equalsIgnoreCase(transactionType);
    }

    /**
     * True when the type is a <em>generic</em> adjustment that needs a
     * written reason. CREDIT / DEBIT are the operator's escape hatches
     * — they can move the ledger for any cause, so a justification is
     * mandatory. Fixed-cause adjustments (LATE_ENROLMENT_CHARGE,
     * LATE_TERMINATION_CREDIT, SCHEME_UPGRADE_ARREARS, etc.) already
     * name their cause in the type itself and skip the reason field.
     */
    private static boolean requiresReason(String transactionType) {
        if (transactionType == null) return false;
        String t = transactionType.toUpperCase();
        return t.equals("CREDIT") || t.equals("DEBIT");
    }

    /** Match BillingService.moneyDisplay: 2dp on the wire for presentation. */
    private static String moneyDisplay(java.math.BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Look up the transaction-type catalogue row by code, read its sign,
     * resolve the affected member/group, and apply the balance delta.
     * Owner resolution prefers the explicit {@link Transaction#getGroupId()}
     * / {@link Transaction#getMemberId()} columns (post-V039), falling
     * back to the linked contribution/invoice for legacy rows that
     * predate the direct-owner model.
     */
    private Mono<Transaction> applyBalanceUpdate(Transaction t) {
        if (t.getTransactionType() == null) return Mono.just(t);

        return transactionTypeRepository.findByCode(t.getTransactionType())
                .flatMap(type -> resolveOwners(t)
                        .flatMap(owners -> {
                            if (owners.memberId() == null && owners.groupId() == null) {
                                // No anchor — nothing to update.
                                return Mono.just(t);
                            }
                            return balanceService.applyTransaction(t, type.getSign(),
                                            owners.memberId(), owners.groupId())
                                    .thenReturn(t);
                        }))
                .defaultIfEmpty(t);
    }

    private Mono<Owners> resolveOwners(Transaction t) {
        // Explicit owner columns win — this is the post-V039 path used by
        // every fresh POST /transactions call.
        if (t.getGroupId() != null || t.getMemberId() != null) {
            return Mono.just(new Owners(t.getMemberId(), t.getGroupId()));
        }
        // Backward-compat: legacy rows only carry a contribution/invoice
        // link. Fall through to the pre-V039 lookup so their balance
        // updates keep working during backfill.
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

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            entityName,
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
