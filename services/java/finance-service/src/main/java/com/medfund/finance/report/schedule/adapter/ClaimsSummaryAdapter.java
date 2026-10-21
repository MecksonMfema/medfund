package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ClaimsSummaryAdapter implements ScheduledReportShapeAdapter {

    private static final String PATH = "/api/v1/reports/CLAIMS_SUMMARY/scheduled-render";

    private final CrossServiceRenderHelper helper;

    @Value("${services.claims.base-url:http://localhost:8083}")
    private String claimsBaseUrl;

    @Override public ReportKey key() { return ReportKey.CLAIMS_SUMMARY; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        return helper.render(ctx, claimsBaseUrl, PATH, "claims.claims-summary.render");
    }
}
