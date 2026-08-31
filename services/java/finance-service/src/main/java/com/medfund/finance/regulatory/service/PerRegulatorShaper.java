package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.ReportKey;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI implemented by every per-regulator shaper (IPEC, CMS, NAIC-P,
 * NAIC-F, PMB, VAT, TAX_WITHHELD, AML). {@link RegulatoryReportShapingService}
 * discovers the concrete shapers via Spring, indexes by
 * {@link #supportedKey()}, and dispatches to the matching one at
 * shape-time. Phase 9 ships the interface + orchestrator; concrete
 * implementations land in phases 10-13, 18, 20-21, 25.
 */
public interface PerRegulatorShaper {

    /** The single {@link ReportKey} this shaper handles. */
    ReportKey supportedKey();

    /**
     * Pull raw data via {@code CrossServiceCallHelper} + finance-service
     * repositories, convert monetary values via {@link RegulatoryFxPolicy},
     * assemble into a {@link RegulatoryReportData} whose reporting currency
     * matches {@link RegulatoryReportCurrency#resolveOrThrow}.
     */
    Mono<RegulatoryReportData> shape(UUID tenantId,
                                     LocalDate periodStart,
                                     LocalDate periodEnd,
                                     String tenantCountryCode);
}
