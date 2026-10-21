package com.medfund.finance.report.schedule.config;

import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.shared.report.ReportKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 17 §A.2 — collects every {@link ScheduledReportShapeAdapter} bean
 * into an {@link EnumMap} keyed by {@link ReportKey}. Duplicate keys throw
 * at boot so a rogue second implementation for the same report can't hide.
 */
@Configuration
public class ScheduledReportAdapterConfig {

    @Bean
    public Map<ReportKey, ScheduledReportShapeAdapter> adaptersByKey(
            List<ScheduledReportShapeAdapter> adapters) {
        Map<ReportKey, ScheduledReportShapeAdapter> map = new EnumMap<>(ReportKey.class);
        for (ScheduledReportShapeAdapter adapter : adapters) {
            ScheduledReportShapeAdapter prior = map.put(adapter.key(), adapter);
            if (prior != null) {
                throw new IllegalStateException("Duplicate ScheduledReportShapeAdapter for key "
                        + adapter.key() + ": " + prior.getClass().getName()
                        + " and " + adapter.getClass().getName());
            }
        }
        return map;
    }
}
