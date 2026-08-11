package com.medfund.claims.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the platform's cost-share buckets + rejection reason codes to the
 * industry-standard CARC (Claim Adjustment Reason Code) and RARC (Remittance
 * Advice Remark Code) pairs surfaced on the member EOB (Phase 4).
 *
 * <p>CARC codes: <a href="https://x12.org/codes/claim-adjustment-reason-codes">
 * X12 catalogue</a>. RARC is optional and only emitted when the platform
 * has a meaningful remark to attach.
 *
 * <p>Deliberately narrow — MVP covers the seven cost-share buckets plus the
 * platform's R01-R18 rejection reasons. Adding a code means appending to
 * the static map here; no external config table.
 */
public final class CarcRarcMapper {

    private CarcRarcMapper() {}

    /**
     * A single CARC/RARC pair with the amount it explains.
     * {@code amount} is a decimal string (matches the Kafka payload shape).
     */
    public record CarcRarc(String carc, String rarc, String amount, String description) {}

    /** Cost-share bucket → CARC (per plan Phase 4 §5). */
    private static final Map<String, CarcRarc> COST_SHARE = new LinkedHashMap<>() {{
        put("deductibleApplied", new CarcRarc("1",  null, null, "Deductible amount"));
        put("copayAmount",       new CarcRarc("3",  null, null, "Co-payment amount"));
        put("coinsuranceAmount", new CarcRarc("2",  null, null, "Coinsurance amount"));
        put("notCoveredAmount",  new CarcRarc("96", null, null, "Non-covered charge(s)"));
        put("shortfallAmount",   new CarcRarc("45", null, null, "Charge exceeds fee schedule/maximum allowable"));
    }};

    /**
     * Build the CARC/RARC list for an adjudicated claim's cost-share
     * breakdown. Skips buckets that are null or zero.
     */
    public static List<CarcRarc> forCostShare(String deductibleApplied,
                                                String copayAmount,
                                                String coinsuranceAmount,
                                                String notCoveredAmount,
                                                String shortfallAmount) {
        List<CarcRarc> out = new java.util.ArrayList<>(5);
        maybeAdd(out, "deductibleApplied", deductibleApplied);
        maybeAdd(out, "copayAmount",       copayAmount);
        maybeAdd(out, "coinsuranceAmount", coinsuranceAmount);
        maybeAdd(out, "notCoveredAmount",  notCoveredAmount);
        maybeAdd(out, "shortfallAmount",   shortfallAmount);
        return out;
    }

    private static void maybeAdd(List<CarcRarc> out, String bucket, String amount) {
        if (amount == null || amount.isBlank()) return;
        try {
            java.math.BigDecimal v = new java.math.BigDecimal(amount);
            if (v.signum() <= 0) return;
        } catch (NumberFormatException e) {
            return;
        }
        CarcRarc template = COST_SHARE.get(bucket);
        if (template == null) return;
        out.add(new CarcRarc(template.carc(), template.rarc(), amount, template.description()));
    }
}
