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

                    String resolvedCurrency = currencyCode != null
                            ? currencyCode
                            : allContribs.stream().map(Contribution::getCurrencyCode)
                                    .filter(c -> c != null && !c.isBlank())
                                    .findFirst().orElse("USD");

                    List<Contribution> matching = allContribs.stream()
                            .filter(c -> resolvedCurrency.equalsIgnoreCase(c.getCurrencyCode()))
                            .toList();
                    Set<UUID> contributionIds = matching.stream().map(Contribution::getId).collect(Collectors.toSet());

                    Mono<List<Transaction>> txnsMono = contributionIds.isEmpty()
                            ? Mono.just(List.of())
                            : findTransactionsForContributions(contributionIds, resolvedCurrency).collectList();

                    return txnsMono.map(txns -> assemble(
                            targetType, targetId, header, periodStart, periodEnd,
                            periodStartInstant, periodEndExclusive,
                            resolvedCurrency, matching, txns, typeSignByCode));
                });
    }

    // ── Assembly ─────────────────────────────────────────────────────────

    private StatementResponse assemble(String targetType, UUID targetId, Header header,
                                        LocalDate periodStart, LocalDate periodEnd,
                                        Instant periodStartInstant, Instant periodEndExclusive,
                                        String currencyCode,
                                        List<Contribution> contributions,
                                        List<Transaction> transactions,
                                        Map<String, String> signByCode) {

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
            String desc = String.format("%s · %s", nz(t.getTransactionType()), nz(t.getPaymentMethod()));
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

    private Flux<Transaction> findTransactionsForContributions(Set<UUID> contributionIds, String currency) {
        return db.sql("""
                SELECT id, transaction_number, contribution_id, invoice_id,
                       amount, currency_code, transaction_type, payment_method,
                       reference, status, transaction_date, created_at, created_by
                  FROM transactions
                 WHERE contribution_id = ANY(:ids)
                   AND currency_code = :currency
                """)
                .bind("ids", contributionIds.toArray(UUID[]::new))
                .bind("currency", currency)
                .map(row -> {
                    Transaction t = new Transaction();
                    t.setId((UUID) row.get("id"));
                    t.setTransactionNumber((String) row.get("transaction_number"));
                    t.setContributionId((UUID) row.get("contribution_id"));
                    t.setInvoiceId((UUID) row.get("invoice_id"));
                    t.setAmount((BigDecimal) row.get("amount"));
                    t.setCurrencyCode((String) row.get("currency_code"));
                    t.setTransactionType((String) row.get("transaction_type"));
                    t.setPaymentMethod((String) row.get("payment_method"));
                    t.setReference((String) row.get("reference"));
                    t.setStatus((String) row.get("status"));
                    t.setTransactionDate((Instant) row.get("transaction_date"));
                    t.setCreatedAt((Instant) row.get("created_at"));
                    t.setCreatedBy((UUID) row.get("created_by"));
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
                    c.setId((UUID) row.get("id"));
                    c.setMemberId((UUID) row.get("member_id"));
                    c.setDependantId((UUID) row.get("dependant_id"));
                    c.setSchemeId((UUID) row.get("scheme_id"));
                    c.setGroupId((UUID) row.get("group_id"));
                    c.setAmount((BigDecimal) row.get("amount"));
                    c.setCurrencyCode((String) row.get("currency_code"));
                    c.setStatus((String) row.get("status"));
                    c.setPeriodStart((LocalDate) row.get("period_start"));
                    c.setPeriodEnd((LocalDate) row.get("period_end"));
                    c.setCreatedAt((Instant) row.get("created_at"));
                    c.setPaidAt((Instant) row.get("paid_at"));
                    c.setPaymentReference((String) row.get("payment_reference"));
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

            return windowedTransactions(inv, lower).collectList()
                    .map(txns -> projectSnapshotStatement(inv, header, rows, txns, typeSigns,
                            targetType, targetId, lower));
        });
    }

    private Flux<Transaction> windowedTransactions(Invoice inv, Instant lower) {
        // Join transactions → contributions to scope by holder. Strict-less-than
        // on the upper bound = exact-instant rule (no transaction appears on
        // two invoices).
        String sql = """
                SELECT t.id, t.transaction_number, t.contribution_id, t.invoice_id,
                       t.amount, t.currency_code, t.transaction_type, t.payment_method,
                       t.reference, t.status, t.transaction_date, t.created_at, t.created_by
                  FROM transactions t
                  JOIN contributions c ON c.id = t.contribution_id
                 WHERE t.currency_code = :currency
                   AND t.created_at <  :upper
                   AND (:lower IS NULL OR t.created_at >= :lower)
                   AND ( (:groupId  IS NOT NULL AND c.group_id  = :groupId)
                      OR (:memberId IS NOT NULL AND c.member_id = :memberId) )
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
                    t.setId((UUID) row.get("id"));
                    t.setTransactionNumber((String) row.get("transaction_number"));
                    t.setContributionId((UUID) row.get("contribution_id"));
                    t.setInvoiceId((UUID) row.get("invoice_id"));
                    t.setAmount((BigDecimal) row.get("amount"));
                    t.setCurrencyCode((String) row.get("currency_code"));
                    t.setTransactionType((String) row.get("transaction_type"));
                    t.setPaymentMethod((String) row.get("payment_method"));
                    t.setReference((String) row.get("reference"));
                    t.setStatus((String) row.get("status"));
                    t.setTransactionDate((Instant) row.get("transaction_date"));
                    t.setCreatedAt((Instant) row.get("created_at"));
                    t.setCreatedBy((UUID) row.get("created_by"));
                    return t;
                })
                .all();
    }

    private StatementResponse projectSnapshotStatement(Invoice inv,
                                                        Header header,
                                                        List<Contribution> rows,
                                                        List<Transaction> txns,
                                                        Map<String, String> typeSigns,
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
            String desc = String.format("%s · %s", nz(t.getTransactionType()), nz(t.getPaymentMethod()));
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

    private record Header(String name, String code) {}
}
