package com.medfund.shared.report;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical registry of every report the InsureFlow platform ships. Each
 * value is the stable key stored in {@code public.tenant_report_config.report_key}
 * — do not rename in place. Adding a new report means adding a value here,
 * annotating the endpoint with {@code @RequiresReport}, and registering it
 * in the Angular {@code ReportCatalogueService}.
 *
 * <p>Grouped by {@link ReportFamily}; per-report cadence guidance lives on
 * the individual value ({@link #isCadenced}) so the tenant-admin schedule
 * UI only exposes the switch for reports that make sense to schedule.
 * The {@link #getPeriodShape() periodShape} companion decides how the
 * Phase 17 probe derives the (periodStart, periodEnd, asOf) triple from
 * the fire time — see {@link ReportPeriodShape}.
 */
@Getter
@RequiredArgsConstructor
public enum ReportKey {

    // ── Billing (Phase 2) ────────────────────────────────────────────────────
    BILLING_REPORT              ("Billing — per scheme",                    ReportFamily.BILLING,          false, null),
    GROUP_BILLING_REPORT        ("Billing — per group",                     ReportFamily.BILLING,          false, null),
    SCHEME_BILLING_DETAIL       ("Scheme billing detail",                   ReportFamily.BILLING,          false, null),
    GROUP_BILLING_DETAIL        ("Group billing detail",                    ReportFamily.BILLING,          false, null),

    // ── Receipts (Phase 3) ───────────────────────────────────────────────────
    RECEIPTS_REPORT             ("Receipts — per scheme / group / member",  ReportFamily.RECEIPTS,         false, null),
    RECEIPTS_AGGREGATE          ("Receipts — drill-down",                   ReportFamily.RECEIPTS,         false, null),
    COLLECTION_RATE             ("Collection rate (receipts vs billing)",   ReportFamily.RECEIPTS,         true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Debtors & member/group balances (Phase 1 retrofit) ───────────────────
    MEMBER_STATEMENT            ("Member statement",                        ReportFamily.DEBTORS,          false, null),
    GROUP_STATEMENT             ("Group statement",                         ReportFamily.DEBTORS,          false, null),
    AGED_DEBTORS                ("Aged debtors",                            ReportFamily.DEBTORS,          true,  ReportPeriodShape.AS_OF_FIRE_TIME),
    BAD_DEBTS                   ("Bad debts",                               ReportFamily.DEBTORS,          false, null),
    DEBTORS_LIST                ("Debtors list",                            ReportFamily.DEBTORS,          false, null),
    MEMBER_BALANCE              ("Member balance",                          ReportFamily.DEBTORS,          false, null),
    GROUP_BALANCE               ("Group balance",                           ReportFamily.DEBTORS,          false, null),
    AGED_BALANCES               ("Aged balances",                           ReportFamily.DEBTORS,          false, null),
    INVOICE_LIST                ("Invoices list",                           ReportFamily.DEBTORS,          false, null),
    INVOICE_DETAIL_PDF          ("Invoice PDF",                             ReportFamily.DEBTORS,          false, null),
    ANNUAL_CAP_UTILIZATION      ("Annual cap utilization",                  ReportFamily.DEBTORS,          false, null),

    // ── Payables / creditors (Phase 1 retrofit) ──────────────────────────────
    CREDITORS                   ("Creditors",                               ReportFamily.PAYABLES,         false, null),
    CREDITOR_PROVIDER_DETAIL    ("Creditor — provider detail",              ReportFamily.PAYABLES,         false, null),
    CREDITOR_MEMBER_DETAIL      ("Creditor — member detail",                ReportFamily.PAYABLES,         false, null),
    PAYMENT_ADVICE              ("Payment advice",                          ReportFamily.PAYABLES,         false, null),
    PAYMENT_ADVICE_DETAIL       ("Payment advice detail",                   ReportFamily.PAYABLES,         false, null),
    PAYMENT_RUNS                ("Payment runs",                            ReportFamily.PAYABLES,         false, null),
    PAYMENT_RUN_ITEMS           ("Payment run items",                       ReportFamily.PAYABLES,         false, null),
    PAYMENT_RUN_WORKBOOK        ("Payment run workbook (multi-currency)",   ReportFamily.PAYABLES,         false, null),
    NOTES                       ("Notes",                                   ReportFamily.PAYABLES,         false, null),
    NOTES_TAX_WITHHELD          ("Notes — tax withheld",                    ReportFamily.PAYABLES,         false, null),
    NOTES_DEBIT                 ("Notes — debit",                           ReportFamily.PAYABLES,         false, null),
    NOTES_CREDIT                ("Notes — credit",                          ReportFamily.PAYABLES,         false, null),
    NOTES_MEMO                  ("Notes — memo",                            ReportFamily.PAYABLES,         false, null),
    ADVANCE_PAYMENTS            ("Advance payments",                        ReportFamily.PAYABLES,         false, null),
    CTC_PAYMENTS                ("CTC payments",                            ReportFamily.PAYABLES,         false, null),
    RECONCILIATIONS             ("Bank reconciliations",                    ReportFamily.PAYABLES,         false, null),

    // ── Claims financial (Phase 4) ───────────────────────────────────────────
    CLAIMS_SUMMARY              ("Claims summary",                          ReportFamily.CLAIMS_FINANCIAL, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    CLAIM_STATUS_LIST           ("Claim status list",                       ReportFamily.CLAIMS_FINANCIAL, false, null),
    CLAIMS_FREQUENCY_SEVERITY   ("Claims frequency & severity",             ReportFamily.CLAIMS_FINANCIAL, false, null),
    DENIAL_ANALYSIS             ("Denial analysis",                         ReportFamily.CLAIMS_FINANCIAL, false, null),
    HIGH_COST_CLAIMANT          ("High-cost claimant",                      ReportFamily.CLAIMS_FINANCIAL, false, null),
    PRE_AUTH_ACTIVITY           ("Pre-auth activity",                       ReportFamily.CLAIMS_FINANCIAL, false, null),

    // ── Cross-service / reconciliation (Phase 5) ─────────────────────────────
    LOSS_RATIO                  ("Loss ratio (billing vs claims)",          ReportFamily.RECONCILIATION,   true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    MEMBER_PAYMENTS_UNIFIED     ("Member payments — unified",               ReportFamily.RECONCILIATION,   false, null),
    PROVIDER_BALANCE_HISTORY    ("Provider balance history",                ReportFamily.RECONCILIATION,   false, null),
    MEMBER_BALANCE_HISTORY      ("Member balance history",                  ReportFamily.RECONCILIATION,   false, null),

    // ── Aged / cash-flow (Phase 8) ───────────────────────────────────────────
    CASH_FLOW_FORECAST_13W      ("Cash flow forecast (13 weeks)",           ReportFamily.RECONCILIATION,   true,  ReportPeriodShape.AS_OF_FIRE_TIME),
    COLLECTION_RATE_TREND       ("Collection rate trend",                   ReportFamily.RECONCILIATION,   false, null),

    // ── Policy lifecycle (Phase 13 §C) ───────────────────────────────────────
    // Family reassignment per L8; cadence flips per L14.
    POLICY_MOVEMENT             ("Policy movement",                         ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    PERSISTENCY_COHORT          ("Persistency cohort",                      ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    GROUP_CENSUS                ("Group census",                            ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    // PROVIDER_NETWORK_UTILIZATION stays under CLAIMS_FINANCIAL (per L8), but cadence flips true (per L14).
    PROVIDER_NETWORK_UTILIZATION("Provider network utilization",            ReportFamily.CLAIMS_FINANCIAL, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Reinsurance (Phase 10) ───────────────────────────────────────────────
    REINSURANCE_CESSION_BORDEREAU ("Reinsurance — cession bordereau",       ReportFamily.REINSURANCE,      true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    REINSURANCE_RECOVERIES        ("Reinsurance — recoveries bordereau",    ReportFamily.REINSURANCE,      true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    REINSURANCE_TREATY_UTILIZATION("Reinsurance — treaty utilization",      ReportFamily.REINSURANCE,      false, null),

    // ── Commission (Phase 11) ────────────────────────────────────────────────
    COMMISSION_STATEMENT        ("Commission statement",                    ReportFamily.COMMISSION,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    COMMISSION_CLAWBACK         ("Commission clawback register",            ReportFamily.COMMISSION,       false, null),

    // ── Underwriting / premium (Phase 12) ────────────────────────────────────
    UPR_MOVEMENT                ("UPR movement",                            ReportFamily.UNDERWRITING,     true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    PREMIUM_REGISTER            ("Premium register",                        ReportFamily.UNDERWRITING,     false, null),
    NEW_BUSINESS_REGISTER       ("New business register",                   ReportFamily.UNDERWRITING,     false, null),
    ENDORSEMENT_REGISTER        ("Endorsement register",                    ReportFamily.UNDERWRITING,     false, null),

    // ── Actuarial (Phase 14) ─────────────────────────────────────────────────
    IBNR_TRIANGLE               ("IBNR triangle",                           ReportFamily.ACTUARIAL,        false, null),
    LOSS_TRIANGLE               ("Loss triangle",                           ReportFamily.ACTUARIAL,        false, null),
    PERSISTENCY_STUDY           ("Persistency study",                       ReportFamily.ACTUARIAL,        false, null),
    MORTALITY_STUDY             ("Mortality study",                         ReportFamily.ACTUARIAL,        false, null),
    MORBIDITY_STUDY             ("Morbidity study",                         ReportFamily.ACTUARIAL,        false, null),
    LAPSE_STUDY                 ("Lapse study",                             ReportFamily.ACTUARIAL,        false, null),

    // ── IFRS 17 (Phase 15) ───────────────────────────────────────────────────
    IFRS17_LRC_LIC_RECONCILIATION       ("IFRS 17 — LRC / LIC reconciliation",       ReportFamily.REGULATORY, true, ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    IFRS17_INSURANCE_REVENUE_SERVICE_RESULT ("IFRS 17 — insurance revenue & service result", ReportFamily.REGULATORY, true, ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Prudential returns (Phase 16 §A) — jurisdiction-gated on top of the toggle ──
    IPEC_QUARTERLY_RETURN       ("IPEC — quarterly return (ZW)",            ReportFamily.PRUDENTIAL,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    CMS_ASR                     ("CMS — annual statutory return (ZA)",      ReportFamily.PRUDENTIAL,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    NAIC_SCHEDULE_P             ("NAIC Schedule P (US)",                    ReportFamily.PRUDENTIAL,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    NAIC_SCHEDULE_F             ("NAIC Schedule F (US)",                    ReportFamily.PRUDENTIAL,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Compliance (Phase 16 §B + §D) — jurisdiction/country-gated ──────────
    PMB_SPEND                   ("PMB spend",                               ReportFamily.COMPLIANCE,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    AML_STR                     ("AML / STR return",                        ReportFamily.COMPLIANCE,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Tax (Phase 16 §C) — country-gated ───────────────────────────────────
    TAX_WITHHELD_RETURN         ("Tax-withheld return",                     ReportFamily.TAX,              true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    VAT_RETURN                  ("VAT return",                              ReportFamily.TAX,              true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),

    // ── Executive KPI (Phase 18) ─────────────────────────────────────────────
    COMBINED_RATIO              ("Combined ratio",                          ReportFamily.DASHBOARD,        false, null),
    LOSS_RATIO_KPI              ("Loss ratio (KPI)",                        ReportFamily.DASHBOARD,        false, null),
    EXPENSE_RATIO               ("Expense ratio",                           ReportFamily.DASHBOARD,        false, null),

    // ── Fraud (Phase 19) ─────────────────────────────────────────────────────
    FRAUD_SIU_REPORT            ("Fraud / SIU report",                      ReportFamily.FRAUD,            true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);

    /** Human-readable label — displayed in the reports hub and settings grid. */
    private final String label;

    /** Family bucket — controls grouping in both surfaces. */
    private final ReportFamily family;

    /**
     * True when the report is one that tenants typically schedule for
     * recurring email delivery. Drives whether the Phase 17 schedule form
     * exposes the switch for this report at all — one-off exports don't
     * make sense to schedule.
     */
    private final boolean cadenced;

    /**
     * Period shape for Phase 17 scheduling — decides how the probe derives
     * (periodStart, periodEnd, asOf) from the fire time. Non-null for every
     * {@link #isCadenced() cadenced} key, {@code null} otherwise. See
     * {@link ReportPeriodShape}.
     */
    private final ReportPeriodShape periodShape;

    /** Wire-shape key persisted in {@code tenant_report_config.report_key}. */
    public String key() {
        return name();
    }

    /** Safe parse — returns {@link Optional#empty()} on an unknown key. */
    public static Optional<ReportKey> parse(String key) {
        if (key == null) return Optional.empty();
        String upper = key.toUpperCase();
        return Arrays.stream(values()).filter(k -> k.name().equals(upper)).findFirst();
    }
}
