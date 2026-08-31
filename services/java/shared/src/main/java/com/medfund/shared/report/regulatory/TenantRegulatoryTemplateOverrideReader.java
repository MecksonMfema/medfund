package com.medfund.shared.report.regulatory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reader for the platform-wide {@code public.tenant_regulatory_template}
 * table. Every Java service that consumes regulatory templates goes through
 * {@link RegulatoryTemplateService#load(UUID, String, String, LocalDate)} —
 * the load path queries this reader first and falls back to the bundled
 * resource lookup when there is no override.
 *
 * <p>Uses the {@code public.} prefix per {@code bug_public_prefix_silent_rollback}
 * — the table is a platform-wide config surface (Phase 4 V168) and every
 * service reads it in-process rather than crossing an HTTP hop to
 * tenancy-service, matching the {@code ReportEnablementReader} pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantRegulatoryTemplateOverrideReader {

    /** Overlay carrying the XLSX bytes + version label for a matched override. */
    public record Overlay(byte[] xlsxBytes, String versionLabel) {}

    private final DatabaseClient databaseClient;

    /**
     * Highest-effective override for {@code (tenant, regulator, reportKey)}
     * whose {@code effective_from ≤ effectiveDate}. Empty when nothing matches
     * so the caller falls through to the bundled path. DB errors resolve to
     * empty (fail-open on templates) with a warn — a Postgres hiccup here
     * shouldn't break every regulator report at once.
     */
    public Mono<Overlay> findEffective(UUID tenantId, String regulator, String reportKey, LocalDate effectiveDate) {
        if (tenantId == null || regulator == null || regulator.isBlank()
                || reportKey == null || reportKey.isBlank() || effectiveDate == null) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                SELECT xlsx_bytes, version_label FROM public.tenant_regulatory_template
                 WHERE tenant_id = :tid
                   AND regulator = :regulator
                   AND report_key = :reportKey
                   AND effective_from <= :effectiveDate
                 ORDER BY effective_from DESC, uploaded_at DESC
                 LIMIT 1
                """)
                .bind("tid", tenantId)
                .bind("regulator", regulator)
                .bind("reportKey", reportKey)
                .bind("effectiveDate", effectiveDate)
                .map((row, meta) -> new Overlay(
                        row.get("xlsx_bytes", byte[].class),
                        row.get("version_label", String.class)))
                .one()
                .onErrorResume(err -> {
                    log.warn("[regulatory-template-override] lookup failed for tenant {} {}/{}: {} — falling back to bundled",
                            tenantId, regulator, reportKey, err.getMessage());
                    return Mono.empty();
                });
    }
}
