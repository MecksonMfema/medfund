package com.medfund.finance.kpi.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.BillingAggregateRow;
import com.medfund.finance.dto.ClaimsIncurredAggregateRow;
import com.medfund.finance.dto.PremiumEarnedAggregateRow;
import com.medfund.finance.kpi.dto.KpiDashboardResponse;
import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.kpi.dto.KpiValue;
import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import com.medfund.finance.producer.service.CommissionAggregateService;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportEnablementReader;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 18 executive KPI composer. Fans out to 4 peer aggregates via
 * {@link CrossServiceCallHelper} (invariant #7), assembles per-currency
 * native ratios (G34) plus a reporting-currency composite scalar (K12),
 * and caches the result in Redis for 15 minutes (K10). Five individual
 * KPI compute paths + one batch endpoint.
 *
 * <p>K13 filter chips: {@code insuranceLine}, {@code schemeId},
 * {@code producerId}. {@code insuranceLine} and {@code schemeId} pass
 * through to the claims + earning-schedule aggregates; {@code producerId}
 * gates the commission aggregate. Billing (written premium) is only
 * sliceable by scheme in v1 — {@code insuranceLine}-filtered EXPENSE_RATIO
 * calls append a warning noting the denominator ignores the line filter
 * until a per-line billing aggregate lands.
 *
 * <p>Small-denominator threshold ({@link #SMALL_DENOMINATOR_THRESHOLD}) is
 * hardcoded at 1000 per K13; a future Phase 18.5 makes it tenant-configurable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiComposerService {

    static final BigDecimal SMALL_DENOMINATOR_THRESHOLD = new BigDecimal("1000");
    static final Duration CACHE_TTL = Duration.ofMinutes(15);
    static final int RATIO_SCALE = 6;
    static final String BASIS_COMBINED_MIXED = "MIXED_LOSS_EARNED_EXPENSE_WRITTEN";

    private final ContributionsClient contributionsClient;
    private final ClaimsClient claimsClient;
    private final CommissionAggregateService commissionAggregateService;
    private final IbnrLookupService ibnrLookupService;
    private final FxRateReader fxRateReader;
    private final ReportingCurrencyResolver currencyResolver;
    private final ReportEnablementReader reportEnablementReader;
    private final ReactiveRedisTemplate<String, KpiReportData> kpiCache;

    private static final List<ReportKey> DASHBOARD_KPI_KEYS = List.of(
            ReportKey.LOSS_RATIO_KPI,
            ReportKey.EXPENSE_RATIO,
            ReportKey.COMBINED_RATIO,
            ReportKey.CLAIMS_FREQUENCY,
            ReportKey.AVERAGE_SEVERITY);

    // ── Public entry points — one per KPI + one batch ────────────────────────

    public Mono<ReportResponse<KpiReportData>> lossRatio(KpiRequest req) {
        return cachedOrCompute(ReportKey.LOSS_RATIO_KPI, req, this::computeLossRatio);
    }

    public Mono<ReportResponse<KpiReportData>> expenseRatio(KpiRequest req) {
        return cachedOrCompute(ReportKey.EXPENSE_RATIO, req, this::computeExpenseRatio);
    }

    public Mono<ReportResponse<KpiReportData>> combinedRatio(KpiRequest req) {
        return cachedOrCompute(ReportKey.COMBINED_RATIO, req, (warnings, r) ->
                Mono.zip(computeLossRatio(warnings, r), computeExpenseRatio(warnings, r))
                        .map(t -> combine(t.getT1(), t.getT2())));
    }

    public Mono<ReportResponse<KpiReportData>> claimsFrequency(KpiRequest req) {
        return cachedOrCompute(ReportKey.CLAIMS_FREQUENCY, req, this::computeClaimsFrequency);
    }

    public Mono<ReportResponse<KpiReportData>> averageSeverity(KpiRequest req) {
        return cachedOrCompute(ReportKey.AVERAGE_SEVERITY, req, this::computeAverageSeverity);
    }

    /**
     * K14 trend endpoint — {@code windowMonths} monthly buckets, oldest first.
     * Each bucket goes through {@link #cachedOrCompute}, so a warm cache serves
     * every bucket without a peer fanout. Buckets end at the first-of-month
     * boundary carried by {@link KpiRequest#periodEnd()} — the controller
     * supplies today's month-start (i.e. the exclusive upper bound of the
     * newest bucket = the last complete month).
     *
     * @param windowMonths 12 or 24; other values raise {@link IllegalArgumentException}
     */
    public Mono<List<KpiTrendPoint>> trend(ReportKey key, KpiRequest req, int windowMonths) {
        if (windowMonths != 12 && windowMonths != 24) {
            return Mono.error(new IllegalArgumentException(
                    "windowMonths must be 12 or 24 (was " + windowMonths + ")"));
        }
        if (!DASHBOARD_KPI_KEYS.contains(key)) {
            return Mono.error(new IllegalArgumentException(
                    "Not a dashboard KPI key: " + key.name()));
        }
        List<KpiRequest> buckets = generateMonthlyBuckets(req, windowMonths);
        return Flux.fromIterable(buckets)
                .concatMap(bucketReq -> dispatchSingle(key, bucketReq)
                        .map(env -> new KpiTrendPoint(
                                bucketReq.periodStart(), bucketReq.periodEnd(),
                                env.data(), env.data().perCurrency(), env.warnings())))
                .collectList();
    }

    private Mono<ReportResponse<KpiReportData>> dispatchSingle(ReportKey key, KpiRequest req) {
        return switch (key) {
            case LOSS_RATIO_KPI   -> lossRatio(req);
            case EXPENSE_RATIO    -> expenseRatio(req);
            case COMBINED_RATIO   -> combinedRatio(req);
            case CLAIMS_FREQUENCY -> claimsFrequency(req);
            case AVERAGE_SEVERITY -> averageSeverity(req);
            default -> Mono.error(new IllegalArgumentException(
                    "Not a dashboard KPI key: " + key.name()));
        };
    }

    private static List<KpiRequest> generateMonthlyBuckets(KpiRequest baseReq, int windowMonths) {
        // Anchor = the exclusive upper bound of the newest bucket (first-of-month).
        // Controller passes today.withDayOfMonth(1); if a caller supplies a mid-month
        // periodEnd, snap up to the next first-of-month so bucket boundaries stay clean.
        LocalDate rawEnd = baseReq.periodEnd() != null
                ? baseReq.periodEnd()
                : LocalDate.now().withDayOfMonth(1);
        LocalDate anchor = rawEnd.getDayOfMonth() == 1
                ? rawEnd
                : rawEnd.withDayOfMonth(1).plusMonths(1);
        List<KpiRequest> buckets = new java.util.ArrayList<>(windowMonths);
        // Emit oldest-first so the sparkline can plot left-to-right without a reverse.
        for (int i = windowMonths; i >= 1; i--) {
            LocalDate bucketStart = anchor.minusMonths(i);
            LocalDate bucketEnd   = anchor.minusMonths(i - 1);
            buckets.add(new KpiRequest(
                    baseReq.tenantId(),
                    bucketStart, bucketEnd,
                    baseReq.reportingCurrency(),
                    baseReq.insuranceLine(),
                    baseReq.schemeId(),
                    baseReq.producerId()));
        }
        return buckets;
    }

    /**
     * K16 batch endpoint — five envelopes in one round-trip. K17: any KPI
     * disabled via {@code public.tenant_report_config} short-circuits the
     * whole payload with a 403 so a tenant that hides one KPI does not get
     * partial batch data that Angular would then have to filter.
     */
    public Mono<KpiDashboardResponse> dashboard(KpiRequest req) {
        return Flux.fromIterable(DASHBOARD_KPI_KEYS)
                .flatMap(key -> reportEnablementReader.isEnabled(req.tenantId(), key)
                        .flatMap(enabled -> enabled
                                ? Mono.empty()
                                : Mono.<ReportKey>error(new ResponseStatusException(
                                        HttpStatus.FORBIDDEN,
                                        "Report is disabled for this tenant: " + key.name()))))
                .then(Mono.zip(lossRatio(req), expenseRatio(req), combinedRatio(req),
                        claimsFrequency(req), averageSeverity(req)))
                .map(t -> {
                    Map<String, ReportResponse<KpiReportData>> tiles = new LinkedHashMap<>();
                    tiles.put(ReportKey.LOSS_RATIO_KPI.name(),   t.getT1());
                    tiles.put(ReportKey.EXPENSE_RATIO.name(),    t.getT2());
                    tiles.put(ReportKey.COMBINED_RATIO.name(),   t.getT3());
                    tiles.put(ReportKey.CLAIMS_FREQUENCY.name(), t.getT4());
                    tiles.put(ReportKey.AVERAGE_SEVERITY.name(), t.getT5());
                    return new KpiDashboardResponse(tiles);
                });
    }

    // ── Compute paths ────────────────────────────────────────────────────────

    private Mono<KpiReportData> computeLossRatio(List<String> warnings, KpiRequest req) {
        Mono<List<ClaimsIncurredAggregateRow>> incurred = CrossServiceCallHelper.guarded(
                "claims-incurred[LINE]",
                claimsClient.claimsIncurred(req.periodStart(), req.periodEnd(),
                        AggregateDimension.LINE.name(), req.insuranceLine(), req.schemeId()),
                List.of(), warnings);
        Mono<List<PremiumEarnedAggregateRow>> earned = CrossServiceCallHelper.guarded(
                "premium-earned[LINE]",
                contributionsClient.earnedPremium(req.periodStart(), req.periodEnd(),
                        AggregateDimension.LINE.name(), req.insuranceLine(), req.schemeId()),
                List.of(), warnings);
        Mono<Optional<BigDecimal>> ibnr = ibnrLookupService.latestIbnrTotal(
                req.tenantId(), req.periodEnd(), req.insuranceLine(), warnings);
        Mono<String> currency = currencyResolver.resolve(req.tenantId(), req.reportingCurrency());
        return Mono.zip(incurred, earned, ibnr, currency)
                .flatMap(t -> assembleLossRatio(t.getT1(), t.getT2(), t.getT3(),
                        t.getT4(), req, warnings));
    }

    private Mono<KpiReportData> computeExpenseRatio(List<String> warnings, KpiRequest req) {
        if (req.insuranceLine() != null && warnings != null) {
            warnings.add("EXPENSE_RATIO denominator (written premium) ignores insuranceLine filter — "
                    + "per-line billing aggregate deferred to Phase 18.5");
        }
        Mono<List<CommissionAggregateRow>> commission = commissionAggregateService.aggregatePaid(
                req.periodStart(), req.periodEnd(), AggregateDimension.LINE,
                req.insuranceLine(), req.producerId())
                .onErrorResume(err -> {
                    log.warn("commission-aggregate compute failed: {}", err.getMessage());
                    if (warnings != null) {
                        warnings.add("commission-aggregate compute failed: "
                                + Objects.toString(err.getMessage(), err.getClass().getSimpleName()));
                    }
                    return Mono.just(List.of());
                });
        Mono<List<BillingAggregateRow>> billing = CrossServiceCallHelper.guarded(
                "billing-aggregate[SCHEME]",
                contributionsClient.aggregateBilling(req.periodStart(), req.periodEnd()),
                List.of(), warnings);
        Mono<String> currency = currencyResolver.resolve(req.tenantId(), req.reportingCurrency());
        return Mono.zip(commission, billing, currency)
                .flatMap(t -> assembleExpenseRatio(t.getT1(), t.getT2(), t.getT3(), req, warnings));
    }

    private Mono<KpiReportData> computeClaimsFrequency(List<String> warnings, KpiRequest req) {
        Mono<List<ClaimsIncurredAggregateRow>> incurred = CrossServiceCallHelper.guarded(
                "claims-incurred[LINE]",
                claimsClient.claimsIncurred(req.periodStart(), req.periodEnd(),
                        AggregateDimension.LINE.name(), req.insuranceLine(), req.schemeId()),
                List.of(), warnings);
        Mono<List<PremiumEarnedAggregateRow>> earned = CrossServiceCallHelper.guarded(
                "premium-earned[LINE]",
                contributionsClient.earnedPremium(req.periodStart(), req.periodEnd(),
                        AggregateDimension.LINE.name(), req.insuranceLine(), req.schemeId()),
                List.of(), warnings);
        Mono<String> currency = currencyResolver.resolve(req.tenantId(), req.reportingCurrency());
        return Mono.zip(incurred, earned, currency)
                .map(t -> assembleClaimsFrequency(t.getT1(), t.getT2(), t.getT3(), warnings));
    }

    private Mono<KpiReportData> computeAverageSeverity(List<String> warnings, KpiRequest req) {
        Mono<List<ClaimsIncurredAggregateRow>> incurred = CrossServiceCallHelper.guarded(
                "claims-incurred[LINE]",
                claimsClient.claimsIncurred(req.periodStart(), req.periodEnd(),
                        AggregateDimension.LINE.name(), req.insuranceLine(), req.schemeId()),
                List.of(), warnings);
        Mono<String> currency = currencyResolver.resolve(req.tenantId(), req.reportingCurrency());
        return Mono.zip(incurred, currency)
                .flatMap(t -> assembleAverageSeverity(t.getT1(), t.getT2(), req, warnings));
    }

    // Overloads used by combinedRatio so both LR and ER share the same warnings sink.
    private Mono<KpiReportData> computeLossRatio(KpiRequest req) {
        return computeLossRatio(new ArrayList<>(), req);
    }

    private Mono<KpiReportData> computeExpenseRatio(KpiRequest req) {
        return computeExpenseRatio(new ArrayList<>(), req);
    }

    // ── Assembly (native per-currency → reporting-currency composite) ────────

    private Mono<KpiReportData> assembleLossRatio(List<ClaimsIncurredAggregateRow> incurred,
                                                  List<PremiumEarnedAggregateRow> earned,
                                                  Optional<BigDecimal> ibnr,
                                                  String reportingCurrency,
                                                  KpiRequest req,
                                                  List<String> warnings) {
        Set<String> currencies = union(currencies(incurred, ClaimsIncurredAggregateRow::currencyCode),
                currencies(earned, PremiumEarnedAggregateRow::currencyCode));
        Map<String, BigDecimal> num = groupSum(currencies, incurred,
                ClaimsIncurredAggregateRow::currencyCode,
                ClaimsIncurredAggregateRow::subtotalIncurredExIbnr);
        Map<String, BigDecimal> den = groupSum(currencies, earned,
                PremiumEarnedAggregateRow::currencyCode,
                PremiumEarnedAggregateRow::earnedPremium);
        Map<String, KpiValue> perCurrency = new LinkedHashMap<>();
        for (String currency : currencies) {
            BigDecimal n = num.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal d = den.getOrDefault(currency, BigDecimal.ZERO);
            perCurrency.put(currency, new KpiValue(safeDivide(n, d), n, d, currency));
        }
        LocalDate asOf = req.periodEnd().minusDays(1);
        return Mono.zip(sumInReportingCurrency(num, reportingCurrency, asOf, req.tenantId()),
                        sumInReportingCurrency(den, reportingCurrency, asOf, req.tenantId()))
                .map(t -> {
                    BigDecimal compositeNum = t.getT1().add(ibnr.orElse(BigDecimal.ZERO));
                    BigDecimal compositeDen = t.getT2();
                    return finalise(compositeNum, compositeDen, reportingCurrency, perCurrency, null, warnings);
                });
    }

    private Mono<KpiReportData> assembleExpenseRatio(List<CommissionAggregateRow> commissions,
                                                     List<BillingAggregateRow> billing,
                                                     String reportingCurrency,
                                                     KpiRequest req,
                                                     List<String> warnings) {
        Set<String> currencies = union(currencies(commissions, CommissionAggregateRow::currencyCode),
                currencies(billing, BillingAggregateRow::currencyCode));
        Map<String, BigDecimal> num = groupSum(currencies, commissions,
                CommissionAggregateRow::currencyCode, CommissionAggregateRow::totalPaid);
        Map<String, BigDecimal> den = groupSum(currencies, billing,
                BillingAggregateRow::currencyCode, BillingAggregateRow::totalBilled);
        Map<String, KpiValue> perCurrency = new LinkedHashMap<>();
        for (String currency : currencies) {
            BigDecimal n = num.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal d = den.getOrDefault(currency, BigDecimal.ZERO);
            perCurrency.put(currency, new KpiValue(safeDivide(n, d), n, d, currency));
        }
        LocalDate asOf = req.periodEnd().minusDays(1);
        return Mono.zip(sumInReportingCurrency(num, reportingCurrency, asOf, req.tenantId()),
                        sumInReportingCurrency(den, reportingCurrency, asOf, req.tenantId()))
                .map(t -> finalise(t.getT1(), t.getT2(), reportingCurrency, perCurrency, null, warnings));
    }

    private KpiReportData assembleClaimsFrequency(List<ClaimsIncurredAggregateRow> incurred,
                                                  List<PremiumEarnedAggregateRow> earned,
                                                  String reportingCurrency,
                                                  List<String> warnings) {
        BigDecimal numerator = incurred.stream()
                .map(r -> BigDecimal.valueOf(r.claimCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal denominator = earned.stream()
                .map(r -> BigDecimal.valueOf(r.rowCount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Dimensionless (count per policy-month) — a single reporting-currency
        // entry keeps the tile UI shape uniform across all 5 KPIs.
        Map<String, KpiValue> perCurrency = new LinkedHashMap<>();
        perCurrency.put(reportingCurrency,
                new KpiValue(safeDivide(numerator, denominator), numerator, denominator, reportingCurrency));
        return finalise(numerator, denominator, reportingCurrency, perCurrency, null, warnings);
    }

    private Mono<KpiReportData> assembleAverageSeverity(List<ClaimsIncurredAggregateRow> incurred,
                                                        String reportingCurrency,
                                                        KpiRequest req,
                                                        List<String> warnings) {
        Set<String> currencies = currencies(incurred, ClaimsIncurredAggregateRow::currencyCode);
        Map<String, BigDecimal> num = groupSum(currencies, incurred,
                ClaimsIncurredAggregateRow::currencyCode, ClaimsIncurredAggregateRow::totalPaid);
        // claim-count is count per currency — same GROUP BY as paid_amount
        Map<String, BigDecimal> den = new LinkedHashMap<>();
        for (String currency : currencies) {
            long count = incurred.stream()
                    .filter(r -> currency.equals(r.currencyCode()))
                    .mapToLong(ClaimsIncurredAggregateRow::claimCount)
                    .sum();
            den.put(currency, BigDecimal.valueOf(count));
        }
        Map<String, KpiValue> perCurrency = new LinkedHashMap<>();
        for (String currency : currencies) {
            BigDecimal n = num.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal d = den.getOrDefault(currency, BigDecimal.ZERO);
            perCurrency.put(currency, new KpiValue(safeDivide(n, d), n, d, currency));
        }
        LocalDate asOf = req.periodEnd().minusDays(1);
        BigDecimal totalCount = den.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sumInReportingCurrency(num, reportingCurrency, asOf, req.tenantId())
                .map(compositeNum -> finalise(compositeNum, totalCount, reportingCurrency,
                        perCurrency, null, warnings));
    }

    private KpiReportData combine(KpiReportData loss, KpiReportData expense) {
        BigDecimal ratio = nullSafeAdd(loss.compositeRatio(), expense.compositeRatio());
        // Numerator/denominator for a mixed-basis sum are not directly meaningful
        // (different denominators); expose the LR numerator so downstream drills
        // still have a hook, and the ER numerator collapses into the sum.
        BigDecimal num = nullSafeAdd(loss.compositeNumerator(), expense.compositeNumerator());
        BigDecimal den = loss.compositeDenominator();
        Map<String, KpiValue> merged = new LinkedHashMap<>();
        Set<String> keys = union(loss.perCurrency().keySet(), expense.perCurrency().keySet());
        for (String currency : keys) {
            KpiValue lr = loss.perCurrency().get(currency);
            KpiValue er = expense.perCurrency().get(currency);
            BigDecimal r = nullSafeAdd(lr != null ? lr.ratio() : null,
                    er != null ? er.ratio() : null);
            BigDecimal n = nullSafeAdd(lr != null ? lr.numerator() : null,
                    er != null ? er.numerator() : null);
            BigDecimal d = lr != null ? lr.denominator() : (er != null ? er.denominator() : BigDecimal.ZERO);
            merged.put(currency, new KpiValue(r, n, d, currency));
        }
        return new KpiReportData(ratio, num, den, BASIS_COMBINED_MIXED, merged);
    }

    // ── Envelope + caching plumbing ──────────────────────────────────────────

    private Mono<ReportResponse<KpiReportData>> cachedOrCompute(
            ReportKey key, KpiRequest req,
            java.util.function.BiFunction<List<String>, KpiRequest, Mono<KpiReportData>> supplier) {
        String cacheKey = cacheKey(key, req);
        List<String> warnings = new ArrayList<>();
        return kpiCache.opsForValue().get(cacheKey)
                .onErrorResume(err -> {
                    log.warn("KPI cache read failed for {}: {} — falling through to compute",
                            cacheKey, err.getMessage());
                    return Mono.empty();
                })
                .map(cached -> buildEnvelope(key, req, cached, warnings))
                .switchIfEmpty(Mono.defer(() -> supplier.apply(warnings, req)
                        .flatMap(data -> maybeWriteCache(cacheKey, data, warnings)
                                .thenReturn(buildEnvelope(key, req, data, warnings)))));
    }

    private Mono<Boolean> maybeWriteCache(String cacheKey, KpiReportData data, List<String> warnings) {
        // Skip cache-write if a peer failure poisoned the result — a peer-down
        // compute yields zero-ratio + a "call failed" warning; caching that
        // would serve stale zeros for the whole TTL after the peer recovers.
        // Small-denominator / IBNR-missing warnings are legitimate steady-state
        // conditions and should still cache.
        boolean peerFailed = warnings.stream().anyMatch(w -> w.contains("call failed")
                || w.contains("compute failed"));
        if (peerFailed) {
            log.debug("Skipping KPI cache write for {} — peer failure warnings present", cacheKey);
            return Mono.just(false);
        }
        return kpiCache.opsForValue().set(cacheKey, data, CACHE_TTL)
                .onErrorResume(err -> {
                    log.warn("KPI cache write failed for {}: {} — result still returned",
                            cacheKey, err.getMessage());
                    return Mono.just(false);
                });
    }

    private ReportResponse<KpiReportData> buildEnvelope(ReportKey key, KpiRequest req,
                                                        KpiReportData data, List<String> warnings) {
        ReportPeriod period = new ReportPeriod(req.periodStart(), req.periodEnd(),
                ReportPeriod.PeriodGrain.MONTHLY);
        List<String> finalWarnings = new ArrayList<>(warnings);
        if (data.compositeDenominator() != null
                && data.compositeDenominator().compareTo(SMALL_DENOMINATOR_THRESHOLD) < 0
                && data.compositeDenominator().signum() > 0) {
            finalWarnings.add("Denominator " + data.compositeDenominator() + " "
                    + Objects.toString(data.perCurrency().keySet().stream().findFirst().orElse(""), "")
                    + " below noise threshold — ratio may be unreliable at this slice");
        }
        return new ReportResponse<>(
                key.name(),
                period,
                pickReportingCurrency(req, data),
                data,
                Map.of(),
                Map.of(),
                List.copyOf(finalWarnings),
                OffsetDateTime.now());
    }

    private static String pickReportingCurrency(KpiRequest req, KpiReportData data) {
        if (req.reportingCurrency() != null && !req.reportingCurrency().isBlank()) {
            return req.reportingCurrency().toUpperCase();
        }
        // Best-effort: pull from the first perCurrency entry (composer set it there).
        return data.perCurrency().keySet().stream().findFirst().orElse("USD");
    }

    private KpiReportData finalise(BigDecimal compositeNum, BigDecimal compositeDen,
                                   String reportingCurrency, Map<String, KpiValue> perCurrency,
                                   String basisNote, List<String> warnings) {
        BigDecimal ratio = safeDivide(compositeNum, compositeDen);
        return new KpiReportData(ratio, compositeNum, compositeDen, basisNote, perCurrency);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private Mono<BigDecimal> sumInReportingCurrency(Map<String, BigDecimal> amountsByCurrency,
                                                    String reportingCurrency,
                                                    LocalDate asOf, java.util.UUID tenantId) {
        if (amountsByCurrency.isEmpty()) return Mono.just(BigDecimal.ZERO);
        return Flux.fromIterable(amountsByCurrency.entrySet())
                .flatMap(e -> fxRateReader.convert(e.getValue(), e.getKey(),
                        reportingCurrency, asOf, tenantId))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> Map<String, BigDecimal> groupSum(Set<String> currencies,
                                                        Collection<T> rows,
                                                        Function<T, String> currencyOf,
                                                        Function<T, BigDecimal> amountOf) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String currency : currencies) {
            BigDecimal sum = BigDecimal.ZERO;
            for (T row : rows) {
                if (currency.equals(currencyOf.apply(row))) {
                    BigDecimal amount = amountOf.apply(row);
                    if (amount != null) sum = sum.add(amount);
                }
            }
            out.put(currency, sum);
        }
        return out;
    }

    private static <T> Set<String> currencies(Collection<T> rows, Function<T, String> currencyOf) {
        Set<String> out = new LinkedHashSet<>();
        for (T row : rows) {
            String c = currencyOf.apply(row);
            if (c != null && !c.isBlank()) out.add(c);
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static BigDecimal safeDivide(BigDecimal num, BigDecimal den) {
        if (num == null || den == null || den.signum() == 0) return BigDecimal.ZERO;
        return num.divide(den, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullSafeAdd(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return BigDecimal.ZERO;
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    private static String cacheKey(ReportKey key, KpiRequest req) {
        return "kpi:" + req.tenantId() + ":" + key.name() + ":"
                + req.periodStart() + ":" + req.periodEnd() + ":"
                + Objects.toString(req.reportingCurrency(), "DEFAULT") + ":"
                + Objects.toString(req.insuranceLine(),    "ALL")     + ":"
                + Objects.toString(req.schemeId(),         "ALL")     + ":"
                + Objects.toString(req.producerId(),       "ALL");
    }
}
