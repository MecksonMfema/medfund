package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import reactor.core.publisher.Mono;

/**
 * Phase 17 §A.2 — bridges the heterogeneous per-report shape services onto
 * a uniform "give me the bytes for this fire" contract. Each cadenced
 * report ships an implementation:
 * <ul>
 *   <li>Finance-service-owned keys — direct {@code @Component} implementations
 *       that delegate to a local shape service ({@code CommissionWorkbookService},
 *       {@code LossRatioExcelService}, etc.).</li>
 *   <li>Other-service-owned keys — implementations that delegate via
 *       {@code CrossServiceCallHelper} to the owner service's
 *       {@code /scheduled-render} endpoint (Phase 5).</li>
 * </ul>
 *
 * <p>Adapters are auto-collected into an {@link java.util.EnumMap} by
 * {@code ScheduledReportAdapterConfig} — Spring wires every
 * {@code ScheduledReportShapeAdapter} bean on the classpath, so adding a new
 * one is just dropping a class.
 */
public interface ScheduledReportShapeAdapter {

    ReportKey key();

    ReportPeriodShape periodShape();

    Mono<byte[]> render(ScheduledFireContext ctx);
}
