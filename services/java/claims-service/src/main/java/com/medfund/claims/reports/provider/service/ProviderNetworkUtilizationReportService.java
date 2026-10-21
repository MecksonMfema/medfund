package com.medfund.claims.reports.provider.service;

import com.medfund.claims.client.ProviderClient;
import com.medfund.claims.reports.provider.dto.NetworkTierTotals;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationResult;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationRow;
import com.medfund.claims.reports.provider.repository.ProviderUtilizationQueryRepository;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 13 §C Phase 9 per L12 + L13 + grill note 6. Builds the two-level
 * PROVIDER_NETWORK_UTILIZATION envelope: query rows from
 * {@code ProviderUtilizationQueryRepository}, enrich each row's
 * {@code providerName} + {@code networkTier} via
 * {@link ProviderClient#batchLookup}, then group into per-tier summary
 * totals. Peer-down guarantees a valid report with placeholder names and
 * an envelope warning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderNetworkUtilizationReportService {

    private final ProviderUtilizationQueryRepository queryRepository;
    private final ProviderClient providerClient;
    private final ReportingCurrencyResolver currencyResolver;

    public Mono<ReportResponse<ProviderUtilizationResult>> generate(LocalDate periodStart, LocalDate periodEnd,
                                                                    String insuranceLine, String networkTier,
                                                                    String overrideCurrency) {
        ReportPeriod period = new ReportPeriod(periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return currencyResolver.resolve(tenantId, overrideCurrency)
                    .flatMap(reportingCurrency -> queryRepository
                            .aggregate(periodStart, periodEnd, insuranceLine)
                            .collectList()
                            .flatMap(raw -> enrichAndSummarize(raw, networkTier)
                                    .map(rich -> ReportResponse.of(
                                            ReportKey.PROVIDER_NETWORK_UTILIZATION,
                                            period, reportingCurrency, rich.result(),
                                            perCurrencyTotals(rich.result().detail()),
                                            Map.of(), rich.warnings()))));
        });
    }

    private Mono<EnrichedResult> enrichAndSummarize(List<ProviderUtilizationRow> raw, String tierFilter) {
        if (raw.isEmpty()) {
            return Mono.just(new EnrichedResult(
                    new ProviderUtilizationResult(Map.of(), List.of()),
                    List.of()));
        }
        var providerIds = raw.stream()
                .map(ProviderUtilizationRow::providerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> warnings = new ArrayList<>();
        return providerClient.batchLookup(providerIds, warnings)
                .map(metaMap -> {
                    List<ProviderUtilizationRow> enriched = raw.stream()
                            .map(row -> merge(row, metaMap.get(row.providerId())))
                            .filter(row -> tierFilter == null || tierFilter.isBlank()
                                    || tierFilter.equalsIgnoreCase(row.networkTier()))
                            .toList();
                    return new EnrichedResult(
                            new ProviderUtilizationResult(summarize(enriched), enriched),
                            warnings);
                });
    }

    private static ProviderUtilizationRow merge(ProviderUtilizationRow row,
                                                ProviderClient.ProviderMetadata meta) {
        String name = meta != null ? meta.name() : "Provider Unknown";
        String tier = meta != null ? meta.networkTier() : "STANDARD";
        return new ProviderUtilizationRow(
                row.providerId(), name, tier, row.insuranceLine(), row.currencyCode(),
                row.claimCount(), row.totalClaimed(), row.totalPaid(),
                row.denialCount(), row.uniqueMembers());
    }

    private static Map<String, NetworkTierTotals> summarize(List<ProviderUtilizationRow> rows) {
        Map<String, long[]> counts = new TreeMap<>();      // provider count + claims + denials + members
        Map<String, BigDecimal[]> money = new TreeMap<>(); // claimed + paid
        Map<String, java.util.Set<UUID>> providersByTier = new TreeMap<>();
        Map<String, java.util.Set<UUID>> membersByTier = new TreeMap<>();

        for (ProviderUtilizationRow r : rows) {
            String tier = r.networkTier() != null ? r.networkTier() : "STANDARD";
            counts.computeIfAbsent(tier, k -> new long[]{0, 0});
            counts.get(tier)[0] += r.claimCount();
            counts.get(tier)[1] += r.denialCount();
            money.computeIfAbsent(tier, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            money.get(tier)[0] = money.get(tier)[0].add(r.totalClaimed() != null ? r.totalClaimed() : BigDecimal.ZERO);
            money.get(tier)[1] = money.get(tier)[1].add(r.totalPaid() != null ? r.totalPaid() : BigDecimal.ZERO);
            providersByTier.computeIfAbsent(tier, k -> new java.util.HashSet<>()).add(r.providerId());
            membersByTier.computeIfAbsent(tier, k -> new java.util.HashSet<>()).add(r.providerId());
        }

        Map<String, NetworkTierTotals> out = new LinkedHashMap<>();
        for (String tier : counts.keySet()) {
            out.put(tier, new NetworkTierTotals(
                    tier,
                    providersByTier.get(tier).size(),
                    counts.get(tier)[0],
                    money.get(tier)[0],
                    money.get(tier)[1],
                    counts.get(tier)[1],
                    membersByTier.get(tier).size()));
        }
        return out;
    }

    private static Map<String, PerCurrencyTotal> perCurrencyTotals(List<ProviderUtilizationRow> rows) {
        Map<String, BigDecimal> sum = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ProviderUtilizationRow r : rows) {
            String cur = r.currencyCode() != null ? r.currencyCode() : "USD";
            sum.merge(cur, r.totalPaid() != null ? r.totalPaid() : BigDecimal.ZERO, BigDecimal::add);
            counts.merge(cur, r.claimCount(), Long::sum);
        }
        Map<String, PerCurrencyTotal> out = new LinkedHashMap<>();
        sum.forEach((k, v) -> out.put(k, new PerCurrencyTotal(v, counts.getOrDefault(k, 0L))));
        return out;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private record EnrichedResult(ProviderUtilizationResult result, List<String> warnings) {}
}
