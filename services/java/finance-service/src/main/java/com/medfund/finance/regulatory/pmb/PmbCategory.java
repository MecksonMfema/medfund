package com.medfund.finance.regulatory.pmb;

import java.util.Optional;

/**
 * Rollup groupings for CMS Prescribed Minimum Benefit condition codes.
 *
 * <p>The full PMB catalogue is ~270 conditions; the report aggregates
 * per-code paid amounts up to the six industry-default groupings seeded by
 * {@code V172__seed_pmb_classification_default_rules.sql}. Any code that
 * doesn't map to a known family falls into {@link #OTHER} — including
 * tenant-authored PMBs (custom {@code PMB_CLASSIFICATION} rules) that use
 * codes outside the seeded ranges.
 *
 * <p>The mapping is code-range based so a tenant that adds
 * {@code PMB-015} (another respiratory PMB) picks up the correct rollup
 * without a code change here; ranges follow the V172 numbering convention:
 *
 * <ul>
 *   <li>{@code PMB-001..PMB-019} → {@link #RESPIRATORY}</li>
 *   <li>{@code PMB-020..PMB-029} → {@link #CARDIAC}</li>
 *   <li>{@code PMB-030..PMB-039} → {@link #METABOLIC}</li>
 *   <li>{@code PMB-040..PMB-049} → {@link #ONCOLOGY}</li>
 *   <li>{@code PMB-050..PMB-059} → {@link #MENTAL_HEALTH}</li>
 *   <li>{@code PMB-060..PMB-069} → {@link #RENAL}</li>
 *   <li>everything else → {@link #OTHER}</li>
 * </ul>
 */
public enum PmbCategory {
    RESPIRATORY,
    CARDIAC,
    METABOLIC,
    ONCOLOGY,
    MENTAL_HEALTH,
    RENAL,
    OTHER;

    /**
     * Resolve a raw PMB condition code (case-insensitive) into its rollup
     * category. Malformed or missing codes yield {@link #OTHER} so an
     * unclassifiable claim still contributes to the totals rather than
     * silently disappearing.
     */
    public static PmbCategory forCode(String pmbConditionCode) {
        Integer numeric = extractNumeric(pmbConditionCode);
        if (numeric == null) return OTHER;
        return switch (numeric / 10) {
            case 0 -> RESPIRATORY;   // 001..009
            case 1 -> RESPIRATORY;   // 010..019
            case 2 -> CARDIAC;       // 020..029
            case 3 -> METABOLIC;     // 030..039
            case 4 -> ONCOLOGY;      // 040..049
            case 5 -> MENTAL_HEALTH; // 050..059
            case 6 -> RENAL;         // 060..069
            default -> OTHER;
        };
    }

    private static Integer extractNumeric(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        int dash = trimmed.lastIndexOf('-');
        String tail = dash >= 0 ? trimmed.substring(dash + 1) : trimmed;
        if (tail.isEmpty()) return null;
        try {
            return Optional.of(Integer.parseInt(tail)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
