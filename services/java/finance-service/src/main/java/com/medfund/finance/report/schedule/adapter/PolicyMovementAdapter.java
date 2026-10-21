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
public class PolicyMovementAdapter implements ScheduledReportShapeAdapter {

    private static final String PATH = "/api/v1/reports/POLICY_MOVEMENT/scheduled-render";

    private final CrossServiceRenderHelper helper;

    @Value("${services.user.base-url:http://localhost:8082}")
    private String userBaseUrl;

    @Override public ReportKey key() { return ReportKey.POLICY_MOVEMENT; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        return helper.render(ctx, userBaseUrl, PATH, "user.policy-movement.render");
    }
}
