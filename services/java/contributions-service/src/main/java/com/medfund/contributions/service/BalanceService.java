package com.medfund.contributions.service;

import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.dto.BalanceRow;
import com.medfund.contributions.dto.CreditorRow;
import com.medfund.contributions.dto.GroupBalanceResponse;
import com.medfund.contributions.dto.MemberBalanceResponse;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.BalanceQueryRepository;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.repository.GroupRunningBalanceRepository;
import com.medfund.contributions.repository.MemberRunningBalanceRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the running-balance ledger. Mutates balances in three scenarios:
 *
 * <ul>
 *   <li>{@link #applyContributionDebit(Contribution)} — a contribution row is
 *       persisted; the member balance increases by the contribution amount,
 *       and the group balance does too if the contribution is group-billed.</li>
 *   <li>{@link #applyContributionPaid(Contribution)} — a contribution flips to
 *       "paid"; the member (and group, if applicable) balance decreases.</li>
 *   <li>{@link #applyTransaction(Transaction, String)} — an arbitrary
 *       transaction is recorded; the sign comes from the transaction-type
 *       catalogue ('+' debits the balance, '-' credits it).</li>
 * </ul>
 *
 * <p>Every mutation runs inside the caller's reactive transaction (the
 * {@code @Transactional} on BillingService methods covers us) and publishes
 * both an audit event and a domain Kafka event. Lazy-creates rows on first
 * use via PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final MemberRunningBalanceRepository memberRepo;
    private final GroupRunningBalanceRepository groupRepo;
    private final BalanceQueryRepository queryRepo;
    private final DunningConfigRepository dunningRepo;
    private final DatabaseClient db;
    private final AuditPublisher auditPublisher;
    private final BalanceEventPublisher eventPublisher;

    // ── Mutation paths ────────────────────────────────────────────────────────

    @Transactional
    public Mono<Void> applyContributionDebit(Contribution c) {
        if (c.getAmount() == null || c.getCurrencyCode() == null || c.getMemberId() == null) {
            return Mono.empty();
        }
        return upsertMember(c.getMemberId(), c.getCurrencyCode(), c.getAmount(), true, false,
                "CONTRIBUTION_DEBIT")
                .then(c.getGroupId() != null
                        ? upsertGroup(c.getGroupId(), c.getCurrencyCode(), c.getAmount(), true, false,
                                "CONTRIBUTION_DEBIT")
                        : Mono.empty());
    }

    /**
     * Reverse a previously-applied contribution debit. Called by
     * {@code BillingService.revokeBilling} before the {@code contributions}
     * row is deleted so the running balance ledger stays consistent.
     *
     * <p>Symmetric to {@link #applyContributionDebit(Contribution)}: subtracts
     * the same amount from the member balance (and group balance, when the
     * contribution was group-billed). Does NOT touch {@code last_charge_at}
     * or {@code last_payment_at} — a revoke is an undo, not a new
     * charge/payment activity, so the dunning timestamps stay frozen at
     * whatever the most recent real event set them to.
     */
    @Transactional
    public Mono<Void> reverseContributionDebit(Contribution c) {
        if (c.getAmount() == null || c.getCurrencyCode() == null || c.getMemberId() == null) {
            return Mono.empty();
        }
        BigDecimal neg = c.getAmount().negate();
        return upsertMember(c.getMemberId(), c.getCurrencyCode(), neg, false, false,
                "CONTRIBUTION_REVOKED")
                .then(c.getGroupId() != null
                        ? upsertGroup(c.getGroupId(), c.getCurrencyCode(), neg, false, false,
                                "CONTRIBUTION_REVOKED")
                        : Mono.empty());
    }

    @Transactional
    public Mono<Void> applyContributionPaid(Contribution c) {
        if (c.getAmount() == null || c.getCurrencyCode() == null || c.getMemberId() == null) {
            return Mono.empty();
        }
        BigDecimal credit = c.getAmount().negate();
        return upsertMember(c.getMemberId(), c.getCurrencyCode(), credit, false, true,
                "CONTRIBUTION_PAID")
                .then(c.getGroupId() != null
                        ? upsertGroup(c.getGroupId(), c.getCurrencyCode(), credit, false, true,
                                "CONTRIBUTION_PAID")
                        : Mono.empty());
    }

    /**
     * Zero-out a written-off balance. Standard-accounting write-off:
     * the customer's outstanding is no longer receivable, so subtract
     * {@code amount} from member (and group if present) running balance.
     * The historical fact — the amount, the subject, the date — is
     * captured on the {@code bad_debts} row created by
     * {@code BadDebtService.writeOff} / {@code flagAndWriteOffAggregate}
     * so risk scoring can walk that trail later. This method touches
     * only the current-collectable figure.
     *
     * <p>Reason string is {@code BAD_DEBT_WRITE_OFF} so a ledger audit
     * can distinguish an operator-driven write-off from a normal
     * revoke ({@code CONTRIBUTION_REVOKED}) or payment
     * ({@code CONTRIBUTION_PAID}).
     */
    @Transactional
    public Mono<Void> writeOffBalance(UUID memberId, UUID groupId,
                                       String currencyCode, BigDecimal amount) {
        if (currencyCode == null || amount == null) return Mono.empty();
        if (memberId == null && groupId == null) return Mono.empty();
        BigDecimal neg = amount.negate();
        Mono<Void> memberLeg = memberId != null
                ? upsertMember(memberId, currencyCode, neg, false, false, "BAD_DEBT_WRITE_OFF")
                : Mono.empty();
        Mono<Void> groupLeg = groupId != null
                ? upsertGroup(groupId, currencyCode, neg, false, false, "BAD_DEBT_WRITE_OFF")
                : Mono.empty();
        return memberLeg.then(groupLeg);
    }

    /**
     * Apply an arbitrary transaction. {@code sign} comes from the
     * {@code transaction_types.sign} column ('+' or '-'). Transactions
     * without a contribution_id and without an invoice_id are no-ops because
     * we don't know which balance to touch.
     */
    @Transactional
    public Mono<Void> applyTransaction(Transaction t, String sign, UUID memberId, UUID groupId) {
        if (t.getAmount() == null || t.getCurrencyCode() == null || sign == null) {
            return Mono.empty();
        }
        if (memberId == null && groupId == null) {
            return Mono.empty();
        }
        BigDecimal delta = "-".equals(sign) ? t.getAmount().negate() : t.getAmount();
        boolean stampPayment = "-".equals(sign);
        boolean stampCharge  = "+".equals(sign);
        String reason = "TRANSACTION_" + ("-".equals(sign) ? "CREDIT" : "DEBIT");

        Mono<Void> memberLeg = memberId != null
                ? upsertMember(memberId, t.getCurrencyCode(), delta, stampCharge, stampPayment, reason)
                : Mono.empty();
        Mono<Void> groupLeg = groupId != null
                ? upsertGroup(groupId, t.getCurrencyCode(), delta, stampCharge, stampPayment, reason)
                : Mono.empty();
        return memberLeg.then(groupLeg);
    }

    // ── Read paths ────────────────────────────────────────────────────────────

    public Mono<MemberBalanceResponse> getMemberBalance(UUID memberId, String currency) {
        return memberRepo.findByMemberAndCurrency(memberId, currency)
                .map(MemberBalanceResponse::from)
                .defaultIfEmpty(MemberBalanceResponse.zero(memberId, currency));
    }

    public Mono<GroupBalanceResponse> getGroupBalance(UUID groupId, String currency) {
        return groupRepo.findByGroupAndCurrency(groupId, currency)
                .map(GroupBalanceResponse::from)
                .defaultIfEmpty(GroupBalanceResponse.zero(groupId, currency));
    }

    public Mono<PageResponse<CreditorRow>> listCreditors(String currency, String q, int page, int size) {
        int offset = page * size;
        return queryRepo.findCreditors(currency, q, size, offset)
                .map(CreditorRow::from)
                .collectList()
                .zipWith(queryRepo.countCreditors(currency, q))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Mono<PageResponse<BadDebtRow>> listAged(String currency, Integer minAgeDays, String q, int page, int size) {
        return resolveMinAge(minAgeDays)
                .flatMap(threshold -> queryRepo.findAged(currency, threshold, q, size, page * size)
                        .map(row -> BadDebtRow.from(row, classify(row, threshold)))
                        .collectList()
                        .zipWith(queryRepo.countAged(currency, threshold, q))
                        .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size)));
    }

    /**
     * V043 auto-reactivate helper — returns the set of subject ids that
     * are CURRENTLY aged past the SUSPENDED threshold (across all
     * currencies). Consumers reactivate any arrears-suspended row whose
     * id is NOT in this set, because "not aged anymore" means their
     * payment cleared the debt below the threshold. Cheap when total
     * aged rows fit in memory (thousands, not millions).
     */
    public Mono<java.util.Set<UUID>> currentlyAgedSubjectIds() {
        return resolveMinAge(null)
                .flatMap(threshold -> queryRepo.findAged(null, threshold, null, 10_000, 0)
                        .map(BalanceRow::subjectId)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private Mono<Integer> resolveMinAge(Integer override) {
        if (override != null) return Mono.just(Math.max(override, 0));
        return dunningRepo.findById(DunningConfig.SINGLETON_ID)
                .map(c -> c.getSuspensionDays() != null ? c.getSuspensionDays() : 30)
                .defaultIfEmpty(30);
    }

    private String classify(BalanceRow row, int threshold) {
        Long days = row.daysSinceLastActivity();
        if (days == null) return "AGED";
        if (days >= threshold * 3L) return "WRITE_OFF";
        if (days >= threshold) return "SUSPENDED";
        return "GRACE";
    }

    // ── Upsert helpers ────────────────────────────────────────────────────────

    private Mono<Void> upsertMember(UUID memberId, String currency, BigDecimal delta,
                                    boolean stampCharge, boolean stampPayment, String reason) {
        Instant now = Instant.now();
        return db.sql("""
                INSERT INTO member_running_balance (
                    id, member_id, currency_code, balance,
                    last_charge_at, last_payment_at, updated_at
                ) VALUES (
                    gen_random_uuid(), :memberId, :currency, :delta,
                    CASE WHEN :stampCharge THEN :now ELSE NULL END,
                    CASE WHEN :stampPayment THEN :now ELSE NULL END,
                    :now
                )
                ON CONFLICT (member_id, currency_code) DO UPDATE SET
                    balance         = member_running_balance.balance + EXCLUDED.balance,
                    last_charge_at  = CASE WHEN :stampCharge  THEN EXCLUDED.last_charge_at  ELSE member_running_balance.last_charge_at  END,
                    last_payment_at = CASE WHEN :stampPayment THEN EXCLUDED.last_payment_at ELSE member_running_balance.last_payment_at END,
                    updated_at      = EXCLUDED.updated_at
                RETURNING balance
                """)
                .bind("memberId", memberId)
                .bind("currency", currency)
                .bind("delta", delta)
                .bind("stampCharge", stampCharge)
                .bind("stampPayment", stampPayment)
                .bind("now", now)
                .map(row -> (BigDecimal) row.get("balance"))
                .one()
                .flatMap(newBalance ->
                        publishAudit("MemberRunningBalance", memberId, currency, delta, newBalance, reason)
                                .then(eventPublisher.publishMemberBalance(memberId, currency, newBalance, reason, now)))
                .then();
    }

    private Mono<Void> upsertGroup(UUID groupId, String currency, BigDecimal delta,
                                   boolean stampCharge, boolean stampPayment, String reason) {
        Instant now = Instant.now();
        return db.sql("""
                INSERT INTO group_running_balance (
                    id, group_id, currency_code, balance,
                    last_charge_at, last_payment_at, updated_at
                ) VALUES (
                    gen_random_uuid(), :groupId, :currency, :delta,
                    CASE WHEN :stampCharge THEN :now ELSE NULL END,
                    CASE WHEN :stampPayment THEN :now ELSE NULL END,
                    :now
                )
                ON CONFLICT (group_id, currency_code) DO UPDATE SET
                    balance         = group_running_balance.balance + EXCLUDED.balance,
                    last_charge_at  = CASE WHEN :stampCharge  THEN EXCLUDED.last_charge_at  ELSE group_running_balance.last_charge_at  END,
                    last_payment_at = CASE WHEN :stampPayment THEN EXCLUDED.last_payment_at ELSE group_running_balance.last_payment_at END,
                    updated_at      = EXCLUDED.updated_at
                RETURNING balance
                """)
                .bind("groupId", groupId)
                .bind("currency", currency)
                .bind("delta", delta)
                .bind("stampCharge", stampCharge)
                .bind("stampPayment", stampPayment)
                .bind("now", now)
                .map(row -> (BigDecimal) row.get("balance"))
                .one()
                .flatMap(newBalance ->
                        publishAudit("GroupRunningBalance", groupId, currency, delta, newBalance, reason)
                                .then(eventPublisher.publishGroupBalance(groupId, currency, newBalance, reason, now)))
                .then();
    }

    private Mono<Void> publishAudit(String entityType, UUID entityId, String currency,
                                    BigDecimal delta, BigDecimal newBalance, String reason) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newMap = new LinkedHashMap<>();
            newMap.put("currency", currency);
            newMap.put("delta", delta.toPlainString());
            newMap.put("newBalance", newBalance.toPlainString());
            newMap.put("reason", reason);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    entityType,
                    entityId.toString(),
                    "balance",
                    "UPDATE",
                    AuditActor.SYSTEM_ID,
                    AuditActor.SYSTEM_EMAIL,
                    null,
                    newMap,
                    null,
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}
