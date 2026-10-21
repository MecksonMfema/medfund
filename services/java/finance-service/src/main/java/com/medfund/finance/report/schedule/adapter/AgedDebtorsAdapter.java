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
public class AgedDebtorsAdapter implements ScheduledReportShapeAdapter {

    private static final String PATH = "/api/v1/reports/AGED_DEBTORS/scheduled-render";

    private final CrossServiceRenderHelper helper;

    @Value("${services.contributions.base-url:http://localhost:8084}")
    private String contributionsBaseUrl;

    @Override public ReportKey key() { return ReportKey.AGED_DEBTORS; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.AS_OF_FIRE_TIME; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        return helper.render(ctx, contributionsBaseUrl, PATH, "contributions.aged-debtors.render");
    }
}
