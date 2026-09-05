package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Phase 19 §B Phase 12 — cross-service adapter for {@code FRAUD_SIU_REPORT}.
 * Delegates to claims-service {@code /api/v1/reports/FRAUD_SIU_REPORT/scheduled-render};
 * the per-schedule {@code params.includeSensitiveSheets} flag (default false
 * per FR12) is carried in the wire request's {@code params} field and read
 * by the owner-service handler to gate the AI-calibration + Investigator
 * productivity sheets.
 */
@Component
@RequiredArgsConstructor
public class FraudSiuReportAdapter implements ScheduledReportShapeAdapter {

    private static final String PATH = "/api/v1/reports/FRAUD_SIU_REPORT/scheduled-render";

    private final CrossServiceRenderHelper helper;

    @Value("${services.claims.base-url:http://localhost:8083}")
    private String claimsBaseUrl;

    @Override public ReportKey key() { return ReportKey.FRAUD_SIU_REPORT; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        return helper.render(ctx, claimsBaseUrl, PATH, "claims.fraud-siu.render");
    }
}
