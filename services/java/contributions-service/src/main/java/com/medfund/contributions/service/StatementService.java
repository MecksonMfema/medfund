package com.medfund.contributions.service;

import com.medfund.contributions.dto.StatementLine;
import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.entity.TransactionType;
import com.medfund.contributions.repository.ContributionRepository;
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
