package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryReportCurrency;
import com.medfund.shared.tenant.TenantMetadataReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9 shaping backbone for Phase-16 regulator reports. Per-regulator
 * concrete shapers register via the {@link PerRegulatorShaper} SPI;
 * this service dispatches by {@link ReportKey}, enforces the "no
 * client currency override" rule, and resolves the tenant's country
 * once so shapers don't each rerun the lookup.
 *
 * <p>Phase 9 ships the orchestrator only — concrete shapers land in
 * phases 10-13 (IPEC / CMS / NAIC-P / NAIC-F), 18 (PMB), 20-21 (VAT
 * / tax-withheld) and 25 (AML periodic). An unregistered key surfaces
 * as HTTP 501 so callers get a clear "not implemented yet" instead of
 * a null-dereference.
 */
@Slf4j
@Service
public class RegulatoryReportShapingService {

    private final TenantMetadataReader tenantMetadata;
    private final Map<ReportKey, PerRegulatorShaper> shapers;

    public RegulatoryReportShapingService(TenantMetadataReader tenantMetadata,
                                          List<PerRegulatorShaper> registered) {
        this.tenantMetadata = tenantMetadata;
        Map<ReportKey, PerRegulatorShaper> map = new EnumMap<>(ReportKey.class);
        for (PerRegulatorShaper shaper : registered) {
            ReportKey key = shaper.supportedKey();
            PerRegulatorShaper prior = map.put(key, shaper);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two PerRegulatorShaper beans registered for the same key: " + key
                                + " (" + prior + " and " + shaper + "). Only one shaper per key.");
            }
        }
        this.shapers = Map.copyOf(map);
        log.info("[reg-shaping] registered {} shaper(s): {}",
                shapers.size(), shapers.keySet());
    }

    /**
     * Reject a client-supplied {@code reportingCurrency} for Phase 16 keys
     * — the regulator dictates the currency, tenants cannot override.
     * Surfaced as HTTP 422 so callers know it's a request-shape error
     * (400 would suggest "malformed", which is misleading).
     */
    public static void rejectClientCurrencyOverride(ReportKey key, String override) {
        if (override != null && !override.isBlank()
                && (RegulatoryReportCurrency.fixedFor(key).isPresent()
                        || RegulatoryReportCurrency.isCountryNative(key))) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "reportingCurrency is not overridable for regulator report " + key.name()
                            + " — the currency is dictated by the regulator (see "
                            + "RegulatoryReportCurrency for the resolution rules).");
        }
    }

    /**
     * Shape a report for the given tenant + period. Fetches the tenant's
     * country code once and hands it to the registered per-regulator
     * shaper. Unregistered keys resolve to HTTP 501.
     */
    public Mono<RegulatoryReportData> shape(ReportKey key,
                                            UUID tenantId,
                                            LocalDate periodStart,
                                            LocalDate periodEnd) {
        PerRegulatorShaper shaper = shapers.get(key);
        if (shaper == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "No shaper registered for regulator report " + key.name()
                            + " — Phase 16 sub-phase not yet shipped."));
        }
        return tenantMetadata.load(tenantId)
                .flatMap(meta -> shaper.shape(tenantId, periodStart, periodEnd, meta.countryCode()));
    }

    /** Visible for tests + phase-10+ controllers that need to know which keys are wired. */
    public java.util.Set<ReportKey> registeredKeys() {
        return shapers.keySet();
    }
}
