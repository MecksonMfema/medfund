package com.medfund.finance.service;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdvice.PaymentAdviceLineDto;
import com.medfund.finance.entity.PaymentAdviceLine;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.exception.PaymentNotFoundException;
import com.medfund.finance.repository.PaymentAdviceLineRepository;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.PaymentRunRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
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
    private final DatabaseClient db;
    private final FinanceEventPublisher eventPublisher;
    private final AuditPublisher auditPublisher;

    public Flux<PaymentAdviceRecord> findByRun(UUID paymentRunId) {
        return adviceRepository.findByPaymentRunId(paymentRunId);
    }

    public Flux<PaymentAdviceRecord> findByProvider(UUID providerId) {
        return adviceRepository.findByProviderId(providerId);
    }

    public Flux<PaymentAdviceRecord> findByMember(UUID memberId) {
        return adviceRepository.findByMemberId(memberId);
    }

    public Flux<PaymentAdviceRecord> findAll() {
        return adviceRepository.findAllOrdered();
    }

    /**
     * Filter advices by any combination of run, payee, and period bounds.
     * {@code periodStart} / {@code periodEnd} match against
     * {@code period_end_at} — an advice "belongs to" the month its
     * covering run executed in, so filtering by that column matches the
     * natural user question "show me August's advices."
     *
     * <p>All params are optional; when every filter is null this returns
     * the same list as {@link #findAll()}. Kept in-service (using
     * {@code DatabaseClient} directly) rather than as a separate query
     * repository because the volume is low and there's no pagination to
     * scaffold.
     */
    public Flux<PaymentAdviceRecord> findFiltered(UUID runId, UUID providerId, UUID memberId,
                                                  Instant periodStart, Instant periodEnd) {
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM payment_advices WHERE 1=1 ");
        if (runId       != null) sql.append(" AND payment_run_id = :runId ");
        if (providerId  != null) sql.append(" AND provider_id    = :providerId ");
        if (memberId    != null) sql.append(" AND member_id      = :memberId ");
        if (periodStart != null) sql.append(" AND period_end_at >= :periodStart ");
        if (periodEnd   != null) sql.append(" AND period_end_at <= :periodEnd ");
        sql.append(" ORDER BY issued_at DESC");

        var spec = db.sql(sql.toString());
        if (runId       != null) spec = spec.bind("runId",       runId);
        if (providerId  != null) spec = spec.bind("providerId",  providerId);
        if (memberId    != null) spec = spec.bind("memberId",    memberId);
        if (periodStart != null) spec = spec.bind("periodStart", periodStart);
        if (periodEnd   != null) spec = spec.bind("periodEnd",   periodEnd);
        return spec.map(this::rowToRecord).all();
    }

    private PaymentAdviceRecord rowToRecord(io.r2dbc.spi.Readable row) {
        var r = new PaymentAdviceRecord();
        r.setId(row.get("id", UUID.class));
        r.setPaymentRunId(row.get("payment_run_id", UUID.class));
        r.setProviderId(row.get("provider_id", UUID.class));
        r.setMemberId(row.get("member_id", UUID.class));
        r.setPayeeType(row.get("payee_type", String.class));
        r.setCurrencyCode(row.get("currency_code", String.class));
        r.setTotalAmount(row.get("total_amount", BigDecimal.class));
        r.setClaimCount(row.get("claim_count", Integer.class));
        r.setDocumentUrl(row.get("document_url", String.class));
        r.setExcelUrl(row.get("excel_url", String.class));
        r.setStatus(row.get("status", String.class));
        r.setIssuedAt(row.get("issued_at", Instant.class));
        r.setPeriodStartAt(row.get("period_start_at", Instant.class));
        r.setPeriodEndAt(row.get("period_end_at", Instant.class));
        r.setCarriedInAmount(row.get("carried_in_amount", BigDecimal.class));
        r.setClaimsPaidAmount(row.get("claims_paid_amount", BigDecimal.class));
        r.setCtcAppliedAmount(row.get("ctc_applied_amount", BigDecimal.class));
        r.setAdvanceAppliedAmount(row.get("advance_applied_amount", BigDecimal.class));
        r.setTaxWithheldAmount(row.get("tax_withheld_amount", BigDecimal.class));
        r.setShortfallAmount(row.get("shortfall_amount", BigDecimal.class));
        r.setNetDueAmount(row.get("net_due_amount", BigDecimal.class));
        r.setAdviceNumber(row.get("advice_number", String.class));
        r.setCreatedAt(row.get("created_at", Instant.class));
        return r;
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
            .flatMap(payeeName -> loadCarryForward(payeeType, providerId, memberId, currency, run.getId())
                .flatMap(carry -> {
                    if (carry.signum() > 0) {
                        lines.add(newLine("CARRY_FORWARD", "payment_run", null,
                                "Carried forward from prior run", carry, BigDecimal.ZERO,
                                currency, periodStart, lines.size() + 1));
                    }
                    return loadClaimsPaidLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines)
                        .then(loadCtcAppliedLines(memberId, currency, periodStart, periodEnd, lines))
                        .then(loadAdvanceAppliedLines(providerId, currency, periodStart, periodEnd, lines))
                        .then(loadTaxWithheldLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines))
                        .then(loadShortfallLines(payeeType, providerId, memberId, currency, periodStart, periodEnd, lines))
                        .thenReturn(carry);
                })
                .flatMap(carry -> persistAdvice(run, payeeType, providerId, memberId, payeeName,
                        currency, periodStart, periodEnd, carry, lines))
                .flatMap(this::publishAndAudit));
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
                        postedAt != null ? postedAt : end, lines.size() + 1);
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
                        postedAt != null ? postedAt : end, lines.size() + 1);
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
                        postedAt != null ? postedAt : end, lines.size() + 1);
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

        String sql = "SELECT id, amount, reason, created_at "
                + "  FROM adjustments "
                + " WHERE adjustment_type = 'TAX_WITHHELD' "
                + "   AND " + where
                + "   AND currency_code = :currency "
                + "   AND created_at > :start "
                + "   AND created_at <= :end "
                + " ORDER BY created_at";
        return db.sql(sql)
            .bind("id", payeeId)
            .bind("currency", currency)
            .bind("start", start)
            .bind("end", end)
            .map((row, meta) -> {
                UUID adjId = row.get("id", UUID.class);
                BigDecimal amount = row.get("amount", BigDecimal.class);
                String reason = row.get("reason", String.class);
                Instant postedAt = row.get("created_at", Instant.class);
                return newLine("TAX_WITHHELD", "adjustment", adjId,
                        reason != null && !reason.isBlank() ? reason : "Tax withheld",
                        BigDecimal.ZERO,
                        amount != null ? amount : BigDecimal.ZERO,
                        currency,
                        postedAt != null ? postedAt : end, lines.size() + 1);
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
                        postedAt != null ? postedAt : end, lines.size() + 1);
            })
            .all()
            .doOnNext(lines::add)
            .then();
    }

    private PaymentAdviceLine newLine(String lineType, String referenceType, UUID referenceId,
                                      String description, BigDecimal debit, BigDecimal credit,
                                      String currency, Instant postedAt, int sequence) {
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
        BigDecimal netDue = carriedIn.add(claimsPaid)
                .subtract(ctcApplied)
                .subtract(advanceApplied)
                .subtract(taxWithheld)
                .subtract(shortfall);

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
                    l.getCurrencyCode(), l.getPostedAt(), l.getSequence()))
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
