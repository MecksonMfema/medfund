package com.medfund.finance.regulatory.aml;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Effective per-category threshold amounts for a tenant, as-of a specific
 * date. Resolved from {@code public.tenant_aml_threshold_config} by
 * {@link AmlThresholdReader}. Missing entries default to zero — which the
 * calculator treats as "no threshold configured" (every transaction is
 * above zero, so it'd read as "flag everything"). Tenant admins are
 * expected to configure a full row set; Phase 25b will seed defaults per
 * jurisdiction.
 */
public record AmlThresholds(Map<AmlSummaryRawData.ActivityCategory, BigDecimal> byCategory) {

    public AmlThresholds {
        if (byCategory == null) byCategory = new EnumMap<>(AmlSummaryRawData.ActivityCategory.class);
    }

    public BigDecimal threshold(AmlSummaryRawData.ActivityCategory category) {
        BigDecimal v = byCategory.get(category);
        return v != null ? v : BigDecimal.ZERO;
    }

    public static AmlThresholds empty() {
        return new AmlThresholds(new EnumMap<>(AmlSummaryRawData.ActivityCategory.class));
    }
}
