package com.medfund.contributions.service;

import com.medfund.contributions.dto.StatementLine;
import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.entity.TransactionType;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a member- or group-statement: a chronological ledger between two
 * dates, plus opening and closing balances.
 *
 * <p>The running-balance tables only hold the current state, not history,
 * so we reconstruct the period view from the underlying contribution and
 * transaction rows. Opening balance = sum of every ledger-affecting event
 * dated before {@code periodStart}; in-period lines are rendered in date
 * order with a folded running balance.
 *
 * <p>Three event sources contribute to the ledger:
 * <ol>
 *   <li>Contribution creation — debits the balance by {@code amount} on
 *       {@code created_at}.</li>
 *   <li>Mark-paid via {@code BillingService.recordPayment} — credits the
 *       balance by {@code amount} on {@code paid_at}. No transaction row
 *       exists for this path so we synthesize a CONTRIBUTION_PAID line.</li>
 *   <li>Recorded transactions — applied with the sign from the transaction-type
 *       catalogue ('+' debit, '-' credit) on {@code transaction_date}.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementService {

    private final ContributionRepository contributionRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final InvoiceRepository invoiceRepository;
    private final DatabaseClient db;

    public Mono<StatementResponse> generate(String targetType,
                                             UUID targetId,
                                             LocalDate periodStart,
                                             LocalDate periodEnd,
                                             String currencyCode) {
        if (targetType == null || (!"GROUP".equals(targetType) && !"MEMBER".equals(targetType))) {
            return Mono.error(new IllegalArgumentException("targetType must be GROUP or MEMBER"));
        }
        if (targetId == null || periodStart == null || periodEnd == null) {
            return Mono.error(new IllegalArgumentException("targetId, periodStart and periodEnd are required"));
        }
        if (periodEnd.isBefore(periodStart)) {
            return Mono.error(new IllegalArgumentException("periodEnd must not be before periodStart"));
        }

        Instant periodStartInstant = periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant periodEndExclusive = periodEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        Mono<Header> headerInfo = resolveTargetHeader(targetType, targetId);

        Flux<Contribution> contributions = "GROUP".equals(targetType)
                ? contributionRepository.findByGroupId(targetId)
                : contributionRepository.findByMemberId(targetId);

        return Mono.zip(headerInfo, contributions.collectList(), transactionTypeRepository.findAllOrdered().collectList())
                .flatMap(tuple -> {
                    Header header = tuple.getT1();
                    List<Contribution> allContribs = tuple.getT2();
                    Map<String, String> typeSignByCode = tuple.getT3().stream()
                            .filter(t -> t.getCode() != null && t.getSign() != null)
                            .collect(Collectors.toMap(TransactionType::getCode, TransactionType::getSign,
                                    (a, b) -> a));
                    Map<String, String> typeLabelByCode = tuple.getT3().stream()
                            .filter(t -> t.getCode() != null && t.getLabel() != null)
                            .collect(Collectors.toMap(TransactionType::getCode, TransactionType::getLabel,
                                    (a, b) -> a));

                    String resolvedCurrency = currencyCode != null
                            ? currencyCode
                            : allContribs.stream().map(Contribution::getCurrencyCode)
                                    .filter(c -> c != null && !c.isBlank())
                                    .findFirst().orElse("USD");

                    List<Contribution> matching = allContribs.stream()
                            .filter(c -> resolvedCurrency.equalsIgnoreCase(c.getCurrencyCode()))
                            .toList();
                    Set<UUID> contributionIds = matching.stream().map(Contribution::getId).collect(Collectors.toSet());

                    // Include BOTH transactions attributed to one of this
                    // holder's contributions AND transactions posted
                    // directly against the holder (V039 added the direct
                    // owner columns on transactions; a top-of-group
                    // payment carries group_id NOT NULL + contribution_id
                    // NULL and would otherwise never surface). If there
                    // are no contributions the direct-owner branch still
                    // fires so ledger lines pre-billing are visible too.
                    Mono<List<Transaction>> txnsMono = findTransactionsForHolder(
                            targetType, targetId, contributionIds, resolvedCurrency)
                            .collectList();

                    return txnsMono.map(txns -> assemble(
                            targetType, targetId, header, periodStart, periodEnd,
                            periodStartInstant, periodEndExclusive,
                            resolvedCurrency, matching, txns, typeSignByCode, typeLabelByCode));
                });
    }

    // ── Assembly ─────────────────────────────────────────────────────────

    private StatementResponse assemble(String targetType, UUID targetId, Header header,
                                        LocalDate periodStart, LocalDate periodEnd,
                                        Instant periodStartInstant, Instant periodEndExclusive,
                                        String currencyCode,
                                        List<Contribution> contributions,
                                        List<Transaction> transactions,
                                        Map<String, String> signByCode,
                                        Map<String, String> labelByCode) {

        record Event(Instant date, String type, String description, String reference,
                     BigDecimal delta, UUID sourceId) {}

        List<Event> events = new ArrayList<>();

        for (Contribution c : contributions) {
            // Debit: contribution row born.
            if (c.getCreatedAt() != null && c.getAmount() != null) {
                String desc = String.format("Contribution %s → %s",
                        c.getPeriodStart() != null ? c.getPeriodStart() : "?",
                        c.getPeriodEnd() != null ? c.getPeriodEnd() : "?");
                events.add(new Event(c.getCreatedAt(), "CONTRIBUTION", desc, null, c.getAmount(), c.getId()));
            }
            // Synthetic credit when the legacy mark-paid path flipped status.
            if ("paid".equalsIgnoreCase(c.getStatus()) && c.getPaidAt() != null && c.getAmount() != null) {
                events.add(new Event(c.getPaidAt(), "CONTRIBUTION_PAID", "Marked paid",
                        c.getPaymentReference(), c.getAmount().negate(), c.getId()));
            }
        }
        for (Transaction t : transactions) {
            if (t.getAmount() == null) continue;
            String sign = signByCode.getOrDefault(t.getTransactionType(), "-");
            BigDecimal delta = "-".equals(sign) ? t.getAmount().negate() : t.getAmount();
            Instant when = t.getTransactionDate() != null ? t.getTransactionDate() : t.getCreatedAt();
            String desc = describeTransaction(t, labelByCode);
            events.add(new Event(when, "TRANSACTION", desc, t.getReference(), delta, t.getId()));
        }

        events.sort(Comparator.comparing(Event::date, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal opening = BigDecimal.ZERO;
        List<Event> inPeriod = new ArrayList<>();
        for (Event e : events) {
            if (e.date() == null) continue;
            if (e.date().isBefore(periodStartInstant)) {
                opening = opening.add(e.delta());
            } else if (e.date().isBefore(periodEndExclusive)) {
                inPeriod.add(e);
            }
        }

        BigDecimal running = opening;
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal totalPayments = BigDecimal.ZERO;
        List<StatementLine> lines = new ArrayList<>();
        for (Event e : inPeriod) {
            BigDecimal delta = e.delta();
            running = running.add(delta);
            BigDecimal debit  = delta.signum() > 0 ? delta : null;
            BigDecimal credit = delta.signum() < 0 ? delta.abs() : null;
            if (debit != null)  totalCharges  = totalCharges.add(debit);
            if (credit != null) totalPayments = totalPayments.add(credit);
            lines.add(new StatementLine(e.date(), e.type(), e.description(), e.reference(),
                    debit, credit, running, e.sourceId()));
        }
        BigDecimal closing = running;

        StatementResponse.Header outHeader = new StatementResponse.Header(
                targetType, targetId,
                header != null ? header.name() : null,
                header != null ? header.code() : null,
                periodStart, periodEnd,
                currencyCode, opening, closing, totalCharges, totalPayments);
        return new StatementResponse(outHeader, lines);
    }

    // ── Lookups ──────────────────────────────────────────────────────────

    /**
     * Fetches every transaction attributable to the given holder — either
     * because it's linked to one of the holder's contributions
     * ({@code contribution_id ∈ ids}) OR because it was posted directly
     * against the holder via V039's owner columns
     * ({@code group_id = :targetId} for a GROUP,
     * {@code member_id = :targetId} for a MEMBER). The latter case covers
     * "top of group" payments where the operator credits the group
     * without picking a specific contribution row.
     */
    /** Package-private for unit tests that capture the SQL predicate. */
    Flux<Transaction> findTransactionsForHolder(String targetType, UUID targetId,
                                                 Set<UUID> contributionIds, String currency) {
        boolean isGroup = "GROUP".equalsIgnoreCase(targetType);
        UUID[] ids = contributionIds.isEmpty()
                ? new UUID[]{ new UUID(0L, 0L) }  // sentinel — matches nothing
                : contributionIds.toArray(UUID[]::new);
        String sql = """
                SELECT id, transaction_number, contribution_id, invoice_id,
                       group_id, member_id,
                       amount, currency_code, transaction_type, payment_method,
                       reference, status, transaction_date, created_at, created_by
                  FROM transactions
                 WHERE currency_code = :currency
                   AND (
                        contribution_id = ANY(:ids)
                     OR %s
                   )
                """.formatted(isGroup ? "group_id = :targetId" : "member_id = :targetId");
        return db.sql(sql)
                .bind("ids", ids)
                .bind("currency", currency)
                .bind("targetId", targetId)
                .map(row -> {
                    Transaction t = new Transaction();
                    t.setId(row.get("id", UUID.class));
                    t.setTransactionNumber(row.get("transaction_number", String.class));
                    t.setContributionId(row.get("contribution_id", UUID.class));
                    t.setInvoiceId(row.get("invoice_id", UUID.class));
                    t.setGroupId(row.get("group_id", UUID.class));
                    t.setMemberId(row.get("member_id", UUID.class));
                    t.setAmount(row.get("amount", BigDecimal.class));
                    t.setCurrencyCode(row.get("currency_code", String.class));
                    t.setTransactionType(row.get("transaction_type", String.class));
                    t.setPaymentMethod(row.get("payment_method", String.class));
                    t.setReference(row.get("reference", String.class));
                    t.setStatus(row.get("status", String.class));
                    // R2DBC Postgres returns TIMESTAMPTZ as OffsetDateTime;
                    // ask for Instant explicitly so the driver converts.
                    // Raw casts here throw ClassCastException at runtime —
                    // see the sibling pattern in windowedTransactions.
                    t.setTransactionDate(row.get("transaction_date", Instant.class));
                    t.setCreatedAt(row.get("created_at", Instant.class));
                    t.setCreatedBy(row.get("created_by", UUID.class));
                    return t;
                })
                .all();
    }

    // ── Snapshot-backed per-invoice statement (plan §3d) ─────────────────
    //
    // Reads the snapshot fields directly off the invoice row + the prior
    // invoice's committed_at, then queries the contributions belonging
    // to this invoice and the transactions in the half-open window
    // [prior.committed_at, this.committed_at). The header opening/closing
    // are NEVER recomputed — they are read from the snapshotted columns.
    // The running balance in the line list is derived additively from
    // the snapshotted opening, so the response reconciles by construction.

    public Mono<StatementResponse> generateForInvoice(UUID invoiceId) {
        if (invoiceId == null) {
            return Mono.error(new IllegalArgumentException("invoiceId is required"));
        }
        return invoiceRepository.findById(invoiceId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invoice not found: " + invoiceId)))
                .flatMap(this::assembleSnapshotStatement);
    }

    private Mono<StatementResponse> assembleSnapshotStatement(Invoice inv) {
        String targetType = inv.getGroupId() != null ? "GROUP" : "MEMBER";
        UUID   targetId   = inv.getGroupId() != null ? inv.getGroupId() : inv.getMemberId();

        Mono<Instant> lowerBoundMono = inv.getPriorInvoiceId() != null
                ? invoiceRepository.findById(inv.getPriorInvoiceId())
                        .map(Invoice::getCommittedAt)
                        .defaultIfEmpty(null)
                : Mono.justOrEmpty((Instant) null).defaultIfEmpty(null);

        Mono<List<Contribution>> rowsMono = db.sql("""
                SELECT * FROM contributions WHERE invoice_id = :iid
                """)
                .bind("iid", inv.getId())
                .map((row, meta) -> {
                    Contribution c = new Contribution();
                    c.setId(row.get("id", UUID.class));
                    c.setMemberId(row.get("member_id", UUID.class));
                    c.setDependantId(row.get("dependant_id", UUID.class));
                    c.setSchemeId(row.get("scheme_id", UUID.class));
                    c.setGroupId(row.get("group_id", UUID.class));
                    c.setAmount(row.get("amount", BigDecimal.class));
                    c.setCurrencyCode(row.get("currency_code", String.class));
                    c.setStatus(row.get("status", String.class));
                    c.setPeriodStart(row.get("period_start", LocalDate.class));
                    c.setPeriodEnd(row.get("period_end", LocalDate.class));
                    // R2DBC Postgres driver returns TIMESTAMPTZ as OffsetDateTime,
                    // not Instant — request Instant explicitly so the driver converts.
                    c.setCreatedAt(row.get("created_at", Instant.class));
                    c.setPaidAt(row.get("paid_at", Instant.class));
                    c.setPaymentReference(row.get("payment_reference", String.class));
                    return c;
                })
                .all().collectList();

        return Mono.zip(
                resolveTargetHeader(targetType, targetId),
                rowsMono,
                lowerBoundMono.defaultIfEmpty(Instant.EPOCH),
                transactionTypeRepository.findAllOrdered().collectList()
        ).flatMap(tuple -> {
            Header header                     = tuple.getT1();
            List<Contribution> rows           = tuple.getT2();
            Instant lower                     = Instant.EPOCH.equals(tuple.getT3()) ? null : tuple.getT3();
            Map<String, String> typeSigns     = tuple.getT4().stream()
                    .filter(t -> t.getCode() != null && t.getSign() != null)
                    .collect(Collectors.toMap(TransactionType::getCode, TransactionType::getSign, (a, b) -> a));
            Map<String, String> typeLabels    = tuple.getT4().stream()
                    .filter(t -> t.getCode() != null && t.getLabel() != null)
                    .collect(Collectors.toMap(TransactionType::getCode, TransactionType::getLabel, (a, b) -> a));

            return windowedTransactions(inv, lower).collectList()
                    .map(txns -> projectSnapshotStatement(inv, header, rows, txns, typeSigns, typeLabels,
                            targetType, targetId, lower));
        });
    }

    /** Package-private for unit tests that capture the SQL predicate. */
    Flux<Transaction> windowedTransactions(Invoice inv, Instant lower) {
        // LEFT JOIN so transactions with contribution_id NULL (a top-of-
        // group payment posted directly against the holder) still surface.
        // Predicate matches either the contribution's holder OR the
        // transaction's own group_id/member_id — either path attributes
        // the row to the invoice's holder. Strict-less-than on the upper
        // bound = exact-instant rule (no transaction appears on two
        // invoices).
        String sql = """
                SELECT t.id, t.transaction_number, t.contribution_id, t.invoice_id,
                       t.group_id, t.member_id,
                       t.amount, t.currency_code, t.transaction_type, t.payment_method,
                       t.reference, t.reason, t.status, t.transaction_date, t.created_at, t.created_by
                  FROM transactions t
                  LEFT JOIN contributions c ON c.id = t.contribution_id
                 WHERE t.currency_code = :currency
                   AND t.created_at <  :upper
                   AND (:lower IS NULL OR t.created_at >= :lower)
                   AND ( (:groupId  IS NOT NULL AND (c.group_id  = :groupId  OR t.group_id  = :groupId))
                      OR (:memberId IS NOT NULL AND (c.member_id = :memberId OR t.member_id = :memberId)) )
                """;
        var spec = db.sql(sql)
                .bind("currency", inv.getCurrencyCode())
                .bind("upper",    inv.getCommittedAt())
                .bind("groupId",  inv.getGroupId()  != null ? inv.getGroupId()  : new java.util.UUID(0,0))
                .bind("memberId", inv.getMemberId() != null ? inv.getMemberId() : new java.util.UUID(0,0));
        spec = (lower != null) ? spec.bind("lower", lower) : spec.bindNull("lower", Instant.class);
        return spec
                .map(row -> {
                    Transaction t = new Transaction();
                    t.setId(row.get("id", UUID.class));
                    t.setTransactionNumber(row.get("transaction_number", String.class));
                    t.setContributionId(row.get("contribution_id", UUID.class));
                    t.setInvoiceId(row.get("invoice_id", UUID.class));
                    t.setGroupId(row.get("group_id", UUID.class));
                    t.setMemberId(row.get("member_id", UUID.class));
                    t.setAmount(row.get("amount", BigDecimal.class));
                    t.setCurrencyCode(row.get("currency_code", String.class));
                    t.setTransactionType(row.get("transaction_type", String.class));
                    t.setPaymentMethod(row.get("payment_method", String.class));
                    t.setReference(row.get("reference", String.class));
                    t.setReason(row.get("reason", String.class));
                    t.setStatus(row.get("status", String.class));
                    // R2DBC Postgres returns TIMESTAMPTZ as OffsetDateTime — ask
                    // for Instant explicitly so the driver converts.
                    t.setTransactionDate(row.get("transaction_date", Instant.class));
                    t.setCreatedAt(row.get("created_at", Instant.class));
                    t.setCreatedBy(row.get("created_by", UUID.class));
                    return t;
                })
                .all();
    }

    private StatementResponse projectSnapshotStatement(Invoice inv,
                                                        Header header,
                                                        List<Contribution> rows,
                                                        List<Transaction> txns,
                                                        Map<String, String> typeSigns,
                                                        Map<String, String> typeLabels,
                                                        String targetType,
                                                        UUID targetId,
                                                        Instant lower) {
        BigDecimal opening = inv.getOpeningBalance() != null ? inv.getOpeningBalance() : BigDecimal.ZERO;
        BigDecimal running = opening;

        record Event(Instant date, String type, String description, String reference,
                      BigDecimal delta, UUID sourceId) {}
        List<Event> events = new ArrayList<>();
        for (Contribution c : rows) {
            if (c.getAmount() == null || c.getCreatedAt() == null) continue;
            String desc = String.format("Contribution %s → %s",
                    c.getPeriodStart() != null ? c.getPeriodStart() : "?",
                    c.getPeriodEnd()   != null ? c.getPeriodEnd()   : "?");
            events.add(new Event(c.getCreatedAt(), "CONTRIBUTION", desc, null, c.getAmount(), c.getId()));
        }
        for (Transaction t : txns) {
            if (t.getAmount() == null) continue;
            String sign = typeSigns.getOrDefault(t.getTransactionType(), "-");
            BigDecimal delta = "-".equals(sign) ? t.getAmount().negate() : t.getAmount();
            Instant when = t.getTransactionDate() != null ? t.getTransactionDate() : t.getCreatedAt();
            String desc = describeTransaction(t, typeLabels);
            events.add(new Event(when, "TRANSACTION", desc, t.getReference(), delta, t.getId()));
        }
        events.sort(Comparator.comparing(Event::date, Comparator.nullsLast(Comparator.naturalOrder())));

        List<StatementLine> lines = new ArrayList<>();
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal totalPayments = BigDecimal.ZERO;
        for (Event e : events) {
            running = running.add(e.delta());
            BigDecimal debit  = e.delta().signum() > 0 ? e.delta() : null;
            BigDecimal credit = e.delta().signum() < 0 ? e.delta().abs() : null;
            if (debit  != null) totalCharges  = totalCharges.add(debit);
            if (credit != null) totalPayments = totalPayments.add(credit);
            lines.add(new StatementLine(e.date(), e.type(), e.description(), e.reference(),
                    debit, credit, running, e.sourceId()));
        }

        // Snapshot integrity: trust the snapshot's closing_balance, not
        // the running tally — they should match by construction. If they
        // diverge log a warning so we surface drift loudly.
        BigDecimal snapshotClosing = inv.getClosingBalance() != null ? inv.getClosingBalance() : running;
        if (snapshotClosing.compareTo(running) != 0) {
            log.warn("[statement] invoice={} snapshot closing={} but running={} — investigate",
                    inv.getInvoiceNumber(), snapshotClosing, running);
        }

        StatementResponse.Header outHeader = new StatementResponse.Header(
                targetType, targetId,
                header != null ? header.name() : null,
                header != null ? header.code() : null,
                inv.getPeriodStart(), inv.getPeriodEnd(),
                inv.getCurrencyCode(), opening, snapshotClosing, totalCharges, totalPayments);
        return new StatementResponse(outHeader, lines);
    }

    private Mono<Header> resolveTargetHeader(String targetType, UUID targetId) {
        String sql = "GROUP".equals(targetType)
                ? "SELECT name AS name, registration_number AS code FROM groups WHERE id = :id"
                : "SELECT (first_name || ' ' || last_name) AS name, member_number AS code FROM members WHERE id = :id";
        return db.sql(sql)
                .bind("id", targetId)
                .map(row -> new Header((String) row.get("name"), (String) row.get("code")))
                .one()
                .defaultIfEmpty(new Header(null, null));
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /**
     * Compose the statement-line description for a transaction. Prefers
     * the catalog's friendly label over the raw code so no client-facing
     * surface shows {@code LATE_ENROLMENT_CHARGE} — the label already
     * reads "Late enrolment charge". A CREDIT/DEBIT with a reason
     * appends the reason as the salient detail; otherwise the payment
     * method rides along for context.
     */
    private static String describeTransaction(Transaction t, Map<String, String> labelByCode) {
        String code = t.getTransactionType();
        String label = code != null ? labelByCode.getOrDefault(code, code) : nz(code);
        String reason = t.getReason();
        if (reason != null && !reason.isBlank()) {
            return label + " · " + reason;
        }
        String method = t.getPaymentMethod();
        if (method != null && !method.isBlank()) {
            return label + " · " + method;
        }
        return label;
    }

    private record Header(String name, String code) {}
}
