package com.medfund.finance.ifrs17.service;

import com.medfund.finance.ifrs17.client.Ifrs17PortfolioClient;
import com.medfund.finance.ifrs17.client.Ifrs17PortfolioClient.CohortRow;
import com.medfund.finance.ifrs17.client.Ifrs17PortfolioClient.PortfolioRow;
import com.medfund.finance.ifrs17.dto.Ifrs17ChunkPayload;
import com.medfund.finance.ifrs17.dto.Ifrs17ReportRequest;
import com.medfund.rules.fact.IfrsPortfolioFact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds per portfolio × cohort × currency chunks for an IFRS 17 report
 * submission. For each active portfolio (optionally filtered by
 * {@code request.portfolioIds()}), fires the {@code IFRS17_MODEL} rule to pick
 * the measurement model, then fans out one chunk per (cohort × currency).
 *
 * <p>Phase 17 ships a minimal chunk payload — identifiers + measurement-model
 * discriminator + opening balances defaulted to zero + empty locked-in curve.
 * §18 (aggregator) + §21 (UI) light up as compute results roll in; further
 * phases extend shaping to inline real per-cohort balances from
 * {@code ifrs17_opening_balance_seed}, earning_schedule, claims history,
 * {@code tenant_yield_curve}, and {@code tenant_ra_config}.
 *
 * <p>Currency policy: Phase 17 uses the report's requested
 * {@code reportingCurrency} for every chunk (or {@code USD} default). Multi-
 * currency fan-out (one chunk per currency-of-issue per cohort) lives in a
 * later phase — the request-level reporting currency is the common denominator
 * for the aggregator envelope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17ShapingService {

    private static final String DEFAULT_REPORTING_CURRENCY = "USD";

    private final Ifrs17PortfolioClient portfolioClient;
    private final Ifrs17ModelRuleEvaluator ruleEvaluator;

    /**
     * Fan the report parameters out into one {@link Ifrs17ChunkPayload} per
     * (portfolio × cohort × currency). Firing IFRS17_MODEL per portfolio means
     * a tenant with 5 portfolios × 3 cohorts × 1 currency emits 15 chunks;
     * each chunk becomes one {@code report_job_chunk} row + one Kafka event.
     *
     * <p>When {@code request.portfolioIds()} is null or empty, all active
     * portfolios are shaped. Otherwise the filter is applied client-side on
     * the fetched list — Phase 17 doesn't push it down to user-service since
     * the peer endpoint doesn't yet accept an id-list filter.
     */
    public Flux<Ifrs17ChunkPayload> shapeChunks(UUID tenantId, Ifrs17ReportRequest request) {
        String reportingCurrency = resolveCurrency(request);
        return portfolioClient.listActive()
                .flatMapMany(all -> Flux.fromIterable(filterPortfolios(all, request.portfolioIds())))
                .flatMap(portfolio -> shapePortfolio(tenantId, portfolio, request, reportingCurrency))
                .doOnComplete(() -> log.debug(
                        "[ifrs17-shaping] tenant {} shaping complete for period {} → {}",
                        tenantId, request.periodStart(), request.periodEnd()));
    }

    private Flux<Ifrs17ChunkPayload> shapePortfolio(UUID tenantId, PortfolioRow portfolio,
                                                     Ifrs17ReportRequest request, String reportingCurrency) {
        return portfolioClient.listCohortsByPortfolio(portfolio.id())
                .flatMapMany(cohorts -> Flux.fromIterable(cohorts))
                .filter(c -> Boolean.TRUE.equals(c.isActive()))
                .flatMap(cohort -> evaluateModelAndBuild(
                        tenantId, portfolio, cohort, request, reportingCurrency));
    }

    private Mono<Ifrs17ChunkPayload> evaluateModelAndBuild(
            UUID tenantId, PortfolioRow portfolio, CohortRow cohort,
            Ifrs17ReportRequest request, String reportingCurrency) {
        return ruleEvaluator.evaluate(tenantId,
                        Ifrs17ModelRuleEvaluator.factFor()
                                .portfolioId(portfolio.id())
                                .insuranceLine(portfolio.insuranceLine())
                                .cohortYear(cohort.cohortYear())
                                .reportingCurrency(reportingCurrency)
                                .portfolioName(portfolio.name())
                                .portfolioCreatedAt(portfolio.createdAt()))
                .map(fact -> buildChunk(portfolio, cohort, request, reportingCurrency, fact));
    }

    private Ifrs17ChunkPayload buildChunk(PortfolioRow portfolio, CohortRow cohort,
                                           Ifrs17ReportRequest request, String reportingCurrency,
                                           IfrsPortfolioFact fact) {
        Ifrs17ChunkPayload.Builder builder = Ifrs17ChunkPayload.builder()
                .portfolioId(portfolio.id())
                .cohortId(cohort.id())
                .currency(reportingCurrency)
                .measurementModel(fact.getMeasurementModel())
                .coverageUnitPattern(fact.getCoverageUnitPattern())
                .variableFeePattern(fact.getVariableFeePattern())
                .financeExpensePresentation(fact.getFinanceExpensePresentation())
                .appliedRuleName(fact.getAppliedRuleName())
                .reportingPeriodStart(request.periodStart())
                .reportingPeriodEnd(request.periodEnd());

        Map<String, Object> ifrs17Json = baseIfrs17Payload(portfolio, cohort, request,
                reportingCurrency, fact);
        builder.ifrs17JsonAll(ifrs17Json);
        return builder.build();
    }

    /**
     * Base ai-service compute payload — identifier fields + measurement-model
     * discriminator + zero-balance defaults. Matches the union of
     * {@code PaaChunkInput} / {@code GmmChunkInput} / {@code VfaChunkInput}
     * shapes so any of the three dispatch paths on the Python side can hydrate
     * without a schema mismatch.
     *
     * <p>Phase 17 uses zeros for every balance — the ai-service compute runs but
     * produces zero-value results. §18/§21 extend shaping to inline real
     * balances from the seed table + earning schedule + claims history +
     * tenant yield curve + RA config + expense assumption.
     */
    private Map<String, Object> baseIfrs17Payload(PortfolioRow portfolio, CohortRow cohort,
                                                   Ifrs17ReportRequest request, String reportingCurrency,
                                                   IfrsPortfolioFact fact) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("measurement_model", fact.getMeasurementModel());
        payload.put("job_id", "chunk-placeholder");  // populated per-chunk at insert time by the job service
        payload.put("tenant_id", "");                // ditto
        payload.put("portfolio_id", portfolio.id().toString());
        payload.put("cohort_id", cohort.id().toString());
        payload.put("currency", reportingCurrency);
        payload.put("reporting_period_start", request.periodStart().toString());
        payload.put("reporting_period_end", request.periodEnd().toString());
        payload.put("finance_expense_presentation", fact.getFinanceExpensePresentation());
        if (fact.getAppliedRuleName() != null) {
            payload.put("applied_rule_name", fact.getAppliedRuleName());
        }

        // PAA needs coverage-window boundaries + defaulted settlement days.
        if ("PAA".equals(fact.getMeasurementModel())) {
            payload.put("earliest_coverage_start", request.periodStart().toString());
            payload.put("latest_coverage_end", request.periodEnd().toString());
            payload.put("median_settlement_days", 60);  // <365 → 17.59 opt-out
            payload.put("opening_lrc", BigDecimal.ZERO.toPlainString());
            payload.put("opening_lic", BigDecimal.ZERO.toPlainString());
            payload.put("earned_in_period", BigDecimal.ZERO.toPlainString());
        }

        // GMM needs projection driver inputs — Phase 17 defaults to a 12-month
        // horizon on a nominal 40-year-old cohort; shaping will read actual
        // avg_age + basis grid from tenant tables in a follow-up.
        if ("GMM".equals(fact.getMeasurementModel())) {
            payload.put("insurance_line", portfolio.insuranceLine());
            payload.put("horizon_months", 12);
            payload.put("avg_age", 40);
            payload.put("avg_age_sex", "male");
            payload.put("mortality_multiplier", "1.0");
            payload.put("annual_lapse_rate", "0.0");
            payload.put("premium_per_policy_monthly", "0");
            payload.put("avg_sum_insured", "0");
            payload.put("monthly_expense_per_policy", "0");
            payload.put("locked_in_curve", List.of());
            payload.put("opening_lrc", BigDecimal.ZERO.toPlainString());
            payload.put("opening_lic", BigDecimal.ZERO.toPlainString());
            payload.put("opening_ra", BigDecimal.ZERO.toPlainString());
            payload.put("opening_csm", BigDecimal.ZERO.toPlainString());
        }

        // VFA needs the unit-linked fair-value shape.
        if ("VFA".equals(fact.getMeasurementModel())) {
            payload.put("locked_in_curve", List.of());
            payload.put("opening_underlying_fair_value", BigDecimal.ZERO.toPlainString());
            payload.put("closing_underlying_fair_value", BigDecimal.ZERO.toPlainString());
            payload.put("variable_fee_earned", BigDecimal.ZERO.toPlainString());
            payload.put("variable_fee_pool_remaining", BigDecimal.ZERO.toPlainString());
            payload.put("opening_lrc", BigDecimal.ZERO.toPlainString());
            payload.put("opening_lic", BigDecimal.ZERO.toPlainString());
            payload.put("opening_csm", BigDecimal.ZERO.toPlainString());
            payload.put("opening_loss_component", BigDecimal.ZERO.toPlainString());
        }

        return payload;
    }

    private List<PortfolioRow> filterPortfolios(List<PortfolioRow> all, List<UUID> filter) {
        List<PortfolioRow> active = all.stream()
                .filter(p -> Boolean.TRUE.equals(p.isActive()))
                .toList();
        if (filter == null || filter.isEmpty()) {
            return active;
        }
        return active.stream()
                .filter(p -> filter.contains(p.id()))
                .toList();
    }

    private String resolveCurrency(Ifrs17ReportRequest request) {
        if (request.reportingCurrency() != null && !request.reportingCurrency().isBlank()) {
            return request.reportingCurrency().toUpperCase();
        }
        return DEFAULT_REPORTING_CURRENCY;
    }

    // Bridge for tests: allow a hand-crafted period bound + no cohorts.
    static LocalDate ensurePeriodStart(LocalDate raw) {
        return raw != null ? raw : LocalDate.now().withDayOfMonth(1);
    }
}
