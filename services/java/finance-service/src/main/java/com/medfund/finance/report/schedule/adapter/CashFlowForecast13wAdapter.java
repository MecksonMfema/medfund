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
public class CashFlowForecast13wAdapter implements ScheduledReportShapeAdapter {

    private static final String PATH = "/api/v1/reports/CASH_FLOW_FORECAST_13W/scheduled-render";

    private final CrossServiceRenderHelper helper;

    @Value("${services.contributions.base-url:http://localhost:8084}")
    private String contributionsBaseUrl;

    @Override public ReportKey key() { return ReportKey.CASH_FLOW_FORECAST_13W; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.AS_OF_FIRE_TIME; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        return helper.render(ctx, contributionsBaseUrl, PATH, "contributions.cash-flow-forecast-13w.render");
    }
}
