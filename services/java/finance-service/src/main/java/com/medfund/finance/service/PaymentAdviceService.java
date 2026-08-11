package com.medfund.finance.service;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdvice.PaymentAdviceLineDto;
import com.medfund.finance.dto.PaymentAdviceFilterParams;
import com.medfund.finance.dto.PaymentAdviceRowResponse;
import com.medfund.finance.entity.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentAdviceLineRepository;
import com.medfund.finance.repository.PaymentAdviceQueryRepository;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-payee payment-advice generator. One advice per (run, payee) with
 * a typed ledger of debits (money owed to the payee) and credits
 * (deductions from that balance). Bounded by
 * {@code (prior_run.executed_at, this_run.executed_at]} so the ledger
 * shows every event the payee saw since the last time they were paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAdviceService {

    private final PaymentRunRepository paymentRunRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final PaymentAdviceRecordRepository adviceRepository;
    private final PaymentAdviceLineRepository adviceLineRepository;
    private final PaymentAdviceQueryRepository queryRepository;
    private final DatabaseClient db;
    private final FinanceEventPublisher eventPublisher;
    private final AuditPublisher auditPublisher;

    /**
     * Server-side paginated payment-advices list. Mirrors
     * {@code PaymentRunService.searchPaged}: caps size at 200 to keep any
     * one page cheap, and delegates joins / sort / search to
     * {@link PaymentAdviceQueryRepository}.
     */
    public Mono<PageResponse<PaymentAdviceRowResponse>> searchPaged(PaymentAdviceFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    /** Filtered-set per-currency totals matching the paged query — feeds
     *  {@link com.medfund.shared.report.ReportResponse#perCurrency()}. */
    public Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(PaymentAdviceFilterParams params) {
        return queryRepository.perCurrencyTotals(params);
    }

    public Flux<PaymentAdviceRecord> findByRun(UUID paymentRunId) {
        return adviceRepository.findByPaymentRunId(paymentRunId);
    }

    public Mono<PaymentAdviceRecord> findById(UUID id) {
        return adviceRepository.findById(id);
    }

    public Flux<PaymentAdviceLine> findLines(UUID adviceId) {
        return adviceLineRepository.findByPaymentAdviceIdOrderBySequence(adviceId);
    }

    /**
     * Legacy shim — kept for any lingering callers of the pre-Phase-5
     * single-advice endpoint. Delegates to {@link #generateAdvicesForRun(UUID)}
     * and returns the first per-payee advice, or an empty shell when the
     * run has no eligible items.
     */
    public Mono<PaymentAdvice> generateAdvice(UUID paymentRunId) {
        return generateAdvicesForRun(paymentRunId).next()
            .switchIfEmpty(paymentRunRepository.findById(paymentRunId)
                .switchIfEmpty(Mono.error(new PaymentNotFoundException(paymentRunId)))
                .map(run -> emptyAdviceShell(paymentRunId, run)));
    }

    /**
     * Build one advice per (payeeType, payeeId) for the given run.
     * Idempotent-ish: the UNIQUE indexes on (run, provider) and
     * (run, member) trap duplicate generation with a hard error. Call
     * {@link #regenerateAdvicesForRun(UUID)} to blow away existing rows
     * and rebuild.
     */
    @Transactional
    public Flux<PaymentAdvice> generateAdvicesForRun(UUID paymentRunId) {
        return paymentRunRepository.findById(paymentRunId)
            .switchIfEmpty(Mono.error(new PaymentNotFoundException(paymentRunId)))
            .flatMapMany(run -> resolvePriorExecutedAt(run)
                .flatMapMany(periodStart -> paymentRunItemRepository.findByPaymentRunId(paymentRunId)
                    .groupBy(this::payeeKey)
                    .flatMap(group -> group.collectList()
                        .flatMap(items -> buildAdviceForPayee(run, items, periodStart)))));
    }

    /**
     * Wipe + regenerate all advices for the run. Cascades delete on
     * {@code payment_advice_lines} via V071 ON DELETE CASCADE.
     */
    @Transactional
    public Flux<PaymentAdvice> regenerateAdvicesForRun(UUID paymentRunId) {
        return adviceRepository.deleteByPaymentRunId(paymentRunId)
            .thenMany(generateAdvicesForRun(paymentRunId));
    }

    // ---- Internal ---------------------------------------------------------

    private String payeeKey(PaymentRunItem item) {
        String type = item.getPayeeType() != null ? item.getPayeeType() : "PROVIDER";
        UUID id = "MEMBER".equalsIgnoreCase(type) ? item.getMemberId() : item.getProviderId();
        return type + ":" + (id != null ? id.toString() : "null");
    }

    private Mono<Instant> resolvePriorExecutedAt(PaymentRun run) {
        Instant runEnd = run.getExecutedAt() != null ? run.getExecutedAt() : Instant.now();
        return paymentRunRepository.findMostRecentPriorExecuted(
                run.getCurrencyCode(), runEnd, run.getId())
            .map(prior -> prior.getExecutedAt() != null ? prior.getExecutedAt() : Instant.EPOCH)
            .defaultIfEmpty(run.getCreatedAt() != null ? run.getCreatedAt() : Instant.EPOCH);
    }

    private Mono<PaymentAdvice> buildAdviceForPayee(PaymentRun run,
                                                    List<PaymentRunItem> items,
                                                    Instant periodStart) {
        PaymentRunItem first = items.get(0);
        String payeeType = first.getPayeeType() != null ? first.getPayeeType() : "PROVIDER";
        UUID providerId  = "PROVIDER".equalsIgnoreCase(payeeType) ? first.getProviderId() : null;
        UUID memberId    = "MEMBER".equalsIgnoreCase(payeeType) ? first.getMemberId() : null;
        String currency  = run.getCurrencyCode();
        Instant periodEnd = run.getExecutedAt() != null ? run.getExecutedAt() : Instant.now();

        List<PaymentAdviceLine> lines = new ArrayList<>();

        return loadPayeeName(payeeType, providerId, memberId)
            .flatMap(payeeName -> resolvePriorRunId(run)
                .flatMap(priorRunOpt -> {
                    UUID priorRunId = priorRunOpt.orElse(null);
                    return loadCarryForward(payeeType, providerId, memberId, currency, run.getId())
                        .flatMap(carry -> {
                            if (carry.signum() > 0) {
                                lines.add(newLine("CARRY_FORWARD", "payment_run", null,
                                        "Carried forward from prior run", carry, BigDecimal.ZERO,
                                        currency, periodStart, lines.size() + 1, null));
                            }
                            return loadClaimsPaidLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines)
                                .then(loadCtcAppliedLines(memberId, currency, periodStart, periodEnd, lines))
                                .then(loadAdvanceAppliedLines(providerId, currency, periodStart, periodEnd, lines))
                                .then(loadTaxWithheldLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines))
                                .then(loadShortfallLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines))
                                .then(loadNoteLines(payeeType, providerId, memberId, currency,
                                        periodStart, periodEnd, priorRunId, lines))
                                .thenReturn(carry);
                        })
                        .flatMap(carry -> persistAdvice(run, payeeType, providerId, memberId, payeeName,
                                currency, periodStart, periodEnd, carry, lines))
                        .flatMap(this::publishAndAudit);
                }));
    }

    /**
     * Prior payment-run id for the same currency (as an {@code Optional}
     * because Reactor {@code Mono} can't emit {@code null}). Used to
     * stamp late-arriving notes — those with {@code posted_at} inside a
     * prior run's window but not yet stamped onto any advice — with
     * {@code back_period_run_id}, so the UI can render them as
     * "back-period from run …".
     */
    private Mono<java.util.Optional<UUID>> resolvePriorRunId(PaymentRun run) {
        Instant runEnd = run.getExecutedAt() != null ? run.getExecutedAt() : Instant.now();
        return paymentRunRepository.findMostRecentPriorExecuted(
                run.getCurrencyCode(), runEnd, run.getId())
            .map(prior -> java.util.Optional.ofNullable(prior.getId()))
            .defaultIfEmpty(java.util.Optional.empty());
    }

    private Mono<String> loadPayeeName(String payeeType, UUID providerId, UUID memberId) {
        if ("MEMBER".equalsIgnoreCase(payeeType) && memberId != null) {
            return db.sql("SELECT first_name || ' ' || last_name AS name FROM members WHERE id = :id")
                .bind("id", memberId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .defaultIfEmpty("");
        }
        if (providerId != null) {
            return db.sql("SELECT name FROM providers WHERE id = :id")
                .bind("id", providerId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .defaultIfEmpty("");
        }
        return Mono.just("");
    }

    private Mono<BigDecimal> loadCarryForward(String payeeType, UUID providerId, UUID memberId,
                                              String currency, UUID excludeRunId) {
        String payeeCol = "MEMBER".equalsIgnoreCase(payeeType) ? "member_id" : "provider_id";
        UUID payeeId    = "MEMBER".equalsIgnoreCase(payeeType) ? memberId    : providerId;
        if (payeeId == null) return Mono.just(BigDecimal.ZERO);
        String sql = "SELECT COALESCE(net_due_amount, 0) AS carry FROM payment_advices"
                + " WHERE " + payeeCol + " = :id"
                + "   AND currency_code = :currency"
                + "   AND payment_run_id <> :excludeRunId"
                + " ORDER BY issued_at DESC LIMIT 1";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("excludeRunId", excludeRunId)
            .map((row, meta) -> {
                BigDecimal v = row.get("carry", BigDecimal.class);
                return v != null ? v : BigDecimal.ZERO;
            })
            .one()
            .defaultIfEmpty(BigDecimal.ZERO);
    }

    private Mono<Void> loadClaimsPaidLines(String payeeType, UUID providerId, UUID memberId,
                                           String currency, Instant start, Instant end,
                                           List<PaymentAdviceLine> lines) {
        String where = "MEMBER".equalsIgnoreCase(payeeType)
                ? "payee_type = 'MEMBER' AND member_id = :id"
                : "payee_type = 'PROVIDER' AND provider_id = :id";
        UUID payeeId = "MEMBER".equalsIgnoreCase(payeeType) ? memberId : providerId;
        if (payeeId == null) return Mono.empty();

        String sql = "SELECT id, claim_number, COALESCE(paid_amount, approved_amount, 0) AS amount, adjudicated_at "
                + "  FROM claims "
                + " WHERE " + where
                + "   AND currency_code = :currency "
                + "   AND adjudicated_at > :start "
                + "   AND adjudicated_at <= :end "
                + " ORDER BY adjudicated_at";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID claimId = row.get("id", UUID.class);
                String claimNumber = row.get("claim_number", String.class);
                BigDecimal amount = row.get("amount", BigDecimal.class);
                Instant postedAt = row.get("adjudicated_at", Instant.class);
                return newLine("CLAIM_PAID", "claim", claimId,
                        "Claim " + (claimNumber != null ? claimNumber : ""),
                        amount != null ? amount : BigDecimal.ZERO,
                        BigDecimal.ZERO, currency,
                        postedAt != null ? postedAt : end, lines.size() + 1, null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private Mono<Void> loadCtcAppliedLines(UUID memberId, String currency,
                                           Instant start, Instant end,
                                           List<PaymentAdviceLine> lines) {
        if (memberId == null) return Mono.empty();
        String sql = "SELECT id, amount, committed_at FROM ctc_payments "
                + " WHERE member_id = :id "
                + "   AND currency_code = :currency "
                + "   AND status = 'committed' "
                + "   AND type = 'CTC' "
                + "   AND committed_at > :start "
                + "   AND committed_at <= :end "
                + " ORDER BY committed_at";
        return db.sql(sql)
            .bind("id", memberId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID ctcId = row.get("id", UUID.class);
                BigDecimal amount = row.get("amount", BigDecimal.class);
                Instant postedAt = row.get("committed_at", Instant.class);
                return newLine("CTC_APPLIED", "ctc_payment", ctcId,
                        "CTC offset applied", BigDecimal.ZERO,
                        amount != null ? amount : BigDecimal.ZERO,
                        currency,
                        postedAt != null ? postedAt : end, lines.size() + 1, null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private Mono<Void> loadAdvanceAppliedLines(UUID providerId, String currency,
                                               Instant start, Instant end,
                                               List<PaymentAdviceLine> lines) {
        if (providerId == null) return Mono.empty();
        String sql = "SELECT apa.id, apa.amount_applied, apa.applied_at "
                + "  FROM advance_payment_applications apa "
                + "  JOIN advance_payments ap ON ap.id = apa.advance_payment_id "
                + " WHERE ap.provider_id = :id "
                + "   AND apa.currency_code = :currency "
                + "   AND apa.applied_at > :start "
                + "   AND apa.applied_at <= :end "
                + " ORDER BY apa.applied_at";
        return db.sql(sql)
            .bind("id", providerId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID appId = row.get("id", UUID.class);
                BigDecimal amount = row.get("amount_applied", BigDecimal.class);
                Instant postedAt = row.get("applied_at", Instant.class);
                return newLine("ADVANCE_APPLIED", "advance_payment_application", appId,
                        "Advance payment drawdown", BigDecimal.ZERO,
                        amount != null ? amount : BigDecimal.ZERO,
                        currency,
                        postedAt != null ? postedAt : end, lines.size() + 1, null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private Mono<Void> loadTaxWithheldLines(String payeeType, UUID providerId, UUID memberId,
                                            String currency, Instant start, Instant end,
                                            List<PaymentAdviceLine> lines) {
        String where = "MEMBER".equalsIgnoreCase(payeeType)
                ? "member_id = :id"
                : "provider_id = :id";
        UUID payeeId = "MEMBER".equalsIgnoreCase(payeeType) ? memberId : providerId;
        if (payeeId == null) return Mono.empty();

        // V074: source from renamed `notes` table with note_type='TAX_WITHHELD'.
        // Filter on status='applied' (fixes a latent bug where pending/approved/
        // cancelled TAX_WITHHELD adjustments were counted) and posted_at window
        // instead of created_at — posted_at is the ledger-relevant timestamp.
        String sql = "SELECT id, amount, reason, posted_at "
                + "  FROM notes "
                + " WHERE note_type = 'TAX_WITHHELD' "
                + "   AND status = 'applied' "
                + "   AND " + where
                + "   AND currency_code = :currency "
                + "   AND posted_at > :start "
                + "   AND posted_at <= :end "
                + " ORDER BY posted_at";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID noteId = row.get("id", UUID.class);
                BigDecimal amount = row.get("amount", BigDecimal.class);
                String reason = row.get("reason", String.class);
                Instant postedAt = row.get("posted_at", Instant.class);
                return newLine("TAX_WITHHELD", "note", noteId,
                        reason != null && !reason.isBlank() ? reason : "Tax withheld",
                        BigDecimal.ZERO,
                        amount != null ? amount : BigDecimal.ZERO,
                        currency,
                        postedAt != null ? postedAt : end, lines.size() + 1, null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    /**
     * NOTE_DEBIT / NOTE_CREDIT source (excludes MEMO and TAX_WITHHELD,
     * which have their own handling). Two axes matter:
     * <ul>
     *   <li>{@code direction='DEBIT'} on the note → {@code NOTE_CREDIT}
     *       line on the advice (a debit note is money owed <em>to us</em>
     *       from the payee, which reduces what we pay them).</li>
     *   <li>{@code direction='CREDIT'} → {@code NOTE_DEBIT} line (a
     *       credit note is money we owe the payee <em>beyond</em> the
     *       claims total; increases the payout).</li>
     * </ul>
     * Includes late-arriving notes: any {@code status='applied'} note
     * whose {@code posted_at ≤ periodEnd} and payee matches, provided it
     * isn't already stamped onto any advice line for the same payee. Late
     * ones (those posted <em>before</em> {@code periodStart}) carry
     * {@code back_period_run_id = priorRunId} so the UI can render a
     * back-period badge.
     */
    private Mono<Void> loadNoteLines(String payeeType, UUID providerId, UUID memberId,
                                     String currency, Instant start, Instant end,
                                     UUID priorRunId,
                                     List<PaymentAdviceLine> lines) {
        String where = "MEMBER".equalsIgnoreCase(payeeType)
                ? "n.member_id = :id"
                : "n.provider_id = :id";
        UUID payeeId = "MEMBER".equalsIgnoreCase(payeeType) ? memberId : providerId;
        if (payeeId == null) return Mono.empty();

        String sql = "SELECT n.id, n.amount, n.reason, n.direction, n.note_type, n.posted_at "
                + "  FROM notes n "
                + " WHERE n.status = 'applied' "
                + "   AND n.note_type NOT IN ('MEMO', 'TAX_WITHHELD') "
                + "   AND n.currency_code = :currency "
                + "   AND n.posted_at <= :end "
                + "   AND " + where + " "
                + "   AND NOT EXISTS ("
                + "        SELECT 1 FROM payment_advice_lines pal "
                + "         WHERE pal.reference_type = 'note' "
                + "           AND pal.reference_id = n.id"
                + "   ) "
                + " ORDER BY n.posted_at";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("end", end)
            .map((row, meta) -> {
                UUID noteId = row.get("id", UUID.class);
                BigDecimal amount = row.get("amount", BigDecimal.class);
                String reason = row.get("reason", String.class);
                String direction = row.get("direction", String.class);
                Instant postedAt = row.get("posted_at", Instant.class);

                boolean late = postedAt != null && postedAt.isBefore(start);
                String lineType = "DEBIT".equalsIgnoreCase(direction) ? "NOTE_CREDIT" : "NOTE_DEBIT";
                BigDecimal debit  = "NOTE_DEBIT".equals(lineType)
                        ? (amount != null ? amount : BigDecimal.ZERO) : BigDecimal.ZERO;
                BigDecimal credit = "NOTE_CREDIT".equals(lineType)
                        ? (amount != null ? amount : BigDecimal.ZERO) : BigDecimal.ZERO;
                String desc = reason != null && !reason.isBlank() ? reason : lineType;
                return newLine(lineType, "note", noteId, desc, debit, credit, currency,
                        postedAt != null ? postedAt : end, lines.size() + 1,
                        late ? priorRunId : null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private Mono<Void> loadShortfallLines(String payeeType, UUID providerId, UUID memberId,
                                          String currency, Instant start, Instant end,
                                          List<PaymentAdviceLine> lines) {
        String where = "MEMBER".equalsIgnoreCase(payeeType)
                ? "payee_type = 'MEMBER' AND member_id = :id"
                : "payee_type = 'PROVIDER' AND provider_id = :id";
        UUID payeeId = "MEMBER".equalsIgnoreCase(payeeType) ? memberId : providerId;
        if (payeeId == null) return Mono.empty();

        // Shortfall = amount claimed less amount actually paid, when paid < claimed.
        String sql = "SELECT id, claim_number, "
                + "       (claimed_amount - COALESCE(paid_amount, 0)) AS shortfall, "
                + "       adjudicated_at "
                + "  FROM claims "
                + " WHERE " + where
                + "   AND currency_code = :currency "
                + "   AND adjudicated_at > :start "
                + "   AND adjudicated_at <= :end "
                + "   AND paid_amount IS NOT NULL "
                + "   AND paid_amount < claimed_amount "
                + " ORDER BY adjudicated_at";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID claimId = row.get("id", UUID.class);
                String claimNumber = row.get("claim_number", String.class);
                BigDecimal amount = row.get("shortfall", BigDecimal.class);
                Instant postedAt = row.get("adjudicated_at", Instant.class);
                return newLine("SHORTFALL", "claim", claimId,
                        "Shortfall on claim " + (claimNumber != null ? claimNumber : ""),
                        BigDecimal.ZERO,
                        amount != null ? amount : BigDecimal.ZERO,
                        currency,
                        postedAt != null ? postedAt : end, lines.size() + 1, null);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private PaymentAdviceLine newLine(String lineType, String referenceType, UUID referenceId,
                                      String description, BigDecimal debit, BigDecimal credit,
                                      String currency, Instant postedAt, int sequence,
                                      UUID backPeriodRunId) {
        var line = new PaymentAdviceLine();
        line.setLineType(lineType);
        line.setReferenceType(referenceType);
        line.setReferenceId(referenceId);
        line.setDescription(description);
        line.setDebitAmount(debit != null ? debit : BigDecimal.ZERO);
        line.setCreditAmount(credit != null ? credit : BigDecimal.ZERO);
        line.setCurrencyCode(currency);
        line.setPostedAt(postedAt);
        line.setSequence(sequence);
        line.setBackPeriodRunId(backPeriodRunId);
        return line;
    }

    private Mono<PaymentAdvice> persistAdvice(PaymentRun run, String payeeType,
                                              UUID providerId, UUID memberId, String payeeName,
                                              String currency,
                                              Instant periodStart, Instant periodEnd,
                                              BigDecimal carriedIn,
                                              List<PaymentAdviceLine> lines) {
        BigDecimal claimsPaid = sumBy(lines, "CLAIM_PAID", true);
        BigDecimal ctcApplied = sumBy(lines, "CTC_APPLIED", false);
        BigDecimal advanceApplied = sumBy(lines, "ADVANCE_APPLIED", false);
        BigDecimal taxWithheld = sumBy(lines, "TAX_WITHHELD", false);
        BigDecimal shortfall = sumBy(lines, "SHORTFALL", false);
        BigDecimal noteDebits  = sumBy(lines, "NOTE_DEBIT",  true);
        BigDecimal noteCredits = sumBy(lines, "NOTE_CREDIT", false);
        // V074 formula (Notes rename plan G-decisions):
        //   net_due = carried_in + claims_paid + note_debits
        //             - ctc_applied - advance_applied - tax_withheld
        //             - shortfall - note_credits
        BigDecimal netDue = carriedIn.add(claimsPaid).add(noteDebits)
                .subtract(ctcApplied)
                .subtract(advanceApplied)
                .subtract(taxWithheld)
                .subtract(shortfall)
                .subtract(noteCredits);

        var record = new PaymentAdviceRecord();
        record.setPaymentRunId(run.getId());
        record.setProviderId(providerId);
        record.setMemberId(memberId);
        record.setPayeeType(payeeType);
        record.setCurrencyCode(currency);
        record.setTotalAmount(netDue);
        record.setClaimCount((int) lines.stream().filter(l -> "CLAIM_PAID".equals(l.getLineType())).count());
        record.setStatus("generated");
        record.setIssuedAt(Instant.now());
        record.setPeriodStartAt(periodStart);
        record.setPeriodEndAt(periodEnd);
        record.setCarriedInAmount(carriedIn);
        record.setClaimsPaidAmount(claimsPaid);
        record.setCtcAppliedAmount(ctcApplied);
        record.setAdvanceAppliedAmount(advanceApplied);
        record.setTaxWithheldAmount(taxWithheld);
        record.setShortfallAmount(shortfall);
        record.setNetDueAmount(netDue);
        record.setAdviceNumber(generateAdviceNumber());

        return adviceRepository.save(record)
            .flatMap(saved -> {
                lines.forEach(l -> l.setPaymentAdviceId(saved.getId()));
                return adviceLineRepository.saveAll(lines).then(Mono.just(saved));
            })
            .map(saved -> toDto(saved, run, payeeName, lines));
    }

    private BigDecimal sumBy(List<PaymentAdviceLine> lines, String lineType, boolean debit) {
        return lines.stream()
                .filter(l -> lineType.equals(l.getLineType()))
                .map(l -> {
                    BigDecimal v = debit ? l.getDebitAmount() : l.getCreditAmount();
                    return v != null ? v : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PaymentAdvice toDto(PaymentAdviceRecord record, PaymentRun run, String payeeName,
                                List<PaymentAdviceLine> lines) {
        List<PaymentAdviceLineDto> dtoLines = lines.stream()
            .map(l -> new PaymentAdviceLineDto(
                    l.getLineType(), l.getReferenceType(), l.getReferenceId(),
                    l.getDescription(), l.getDebitAmount(), l.getCreditAmount(),
                    l.getCurrencyCode(), l.getPostedAt(), l.getSequence(),
                    l.getBackPeriodRunId()))
            .toList();
        return new PaymentAdvice(
                record.getAdviceNumber(),
                run.getId(),
                run.getRunNumber(),
                record.getPayeeType(),
                record.getProviderId(),
                "PROVIDER".equals(record.getPayeeType()) ? payeeName : "",
                record.getMemberId(),
                "MEMBER".equals(record.getPayeeType()) ? payeeName : "",
                record.getCurrencyCode(),
                record.getPeriodStartAt(),
                record.getPeriodEndAt(),
                record.getIssuedAt(),
                record.getCarriedInAmount(),
                record.getClaimsPaidAmount(),
                record.getCtcAppliedAmount(),
                record.getAdvanceAppliedAmount(),
                record.getTaxWithheldAmount(),
                record.getShortfallAmount(),
                record.getNetDueAmount(),
                dtoLines);
    }

    private Mono<PaymentAdvice> publishAndAudit(PaymentAdvice advice) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var recordShell = new PaymentAdviceRecord();
            recordShell.setId(UUID.randomUUID());  // event carries adviceId from persisted record — build via lookup
            return adviceRepository.findByPaymentRunIdAndProviderId(advice.paymentRunId(), advice.providerId())
                .switchIfEmpty(advice.memberId() != null
                        ? adviceRepository.findByPaymentRunIdAndMemberId(advice.paymentRunId(), advice.memberId())
                        : Mono.empty())
                .flatMap(record -> eventPublisher.publishAdviceGenerated(record, tenantId)
                    .then(publishAudit(tenantId, "PaymentAdvice",
                            record.getId().toString(), advice.adviceNumber(), "CREATE",
                            AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL,
                            null,
                            Map.of("adviceNumber", advice.adviceNumber(),
                                   "paymentRunId", advice.paymentRunId().toString(),
                                   "payeeType", advice.payeeType(),
                                   "netDue", advice.netDueAmount().toPlainString(),
                                   "lineCount", String.valueOf(advice.lines().size())))))
                .thenReturn(advice);
        });
    }

    private PaymentAdvice emptyAdviceShell(UUID paymentRunId, PaymentRun run) {
        return new PaymentAdvice(
                generateAdviceNumber(),
                paymentRunId,
                run.getRunNumber(),
                "PROVIDER",
                null, "",
                null, "",
                run.getCurrencyCode(),
                null, run.getExecutedAt(),
                Instant.now(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of());
    }

    private String generateAdviceNumber() {
        return "ADV-" + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String entityName, String action,
                                     String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                entityType, entityId, entityName, action,
                actorId, actorEmail,
                oldValue, newValue,
                new String[]{},
                UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }
}
