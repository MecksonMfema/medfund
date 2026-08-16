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
 */
@Getter
@RequiredArgsConstructor
public enum ReportKey {

    // ── Billing (Phase 2) ────────────────────────────────────────────────────
    BILLING_REPORT              ("Billing — per scheme",                    ReportFamily.BILLING,          false),
    GROUP_BILLING_REPORT        ("Billing — per group",                     ReportFamily.BILLING,          false),
    SCHEME_BILLING_DETAIL       ("Scheme billing detail",                   ReportFamily.BILLING,          false),
    GROUP_BILLING_DETAIL        ("Group billing detail",                    ReportFamily.BILLING,          false),

    // ── Receipts (Phase 3) ───────────────────────────────────────────────────
    RECEIPTS_REPORT             ("Receipts — per scheme / group / member",  ReportFamily.RECEIPTS,         false),
    RECEIPTS_AGGREGATE          ("Receipts — drill-down",                   ReportFamily.RECEIPTS,         false),
    COLLECTION_RATE             ("Collection rate (receipts vs billing)",   ReportFamily.RECEIPTS,         true),

    // ── Debtors & member/group balances (Phase 1 retrofit) ───────────────────
    MEMBER_STATEMENT            ("Member statement",                        ReportFamily.DEBTORS,          false),
    GROUP_STATEMENT             ("Group statement",                         ReportFamily.DEBTORS,          false),
    AGED_DEBTORS                ("Aged debtors",                            ReportFamily.DEBTORS,          true),
    BAD_DEBTS                   ("Bad debts",                               ReportFamily.DEBTORS,          false),
    DEBTORS_LIST                ("Debtors list",                            ReportFamily.DEBTORS,          false),
    MEMBER_BALANCE              ("Member balance",                          ReportFamily.DEBTORS,          false),
    GROUP_BALANCE               ("Group balance",                           ReportFamily.DEBTORS,          false),
    AGED_BALANCES               ("Aged balances",                           ReportFamily.DEBTORS,          false),
    INVOICE_LIST                ("Invoices list",                           ReportFamily.DEBTORS,          false),
    INVOICE_DETAIL_PDF          ("Invoice PDF",                             ReportFamily.DEBTORS,          false),
    ANNUAL_CAP_UTILIZATION      ("Annual cap utilization",                  ReportFamily.DEBTORS,          false),

    // ── Payables / creditors (Phase 1 retrofit) ──────────────────────────────
    CREDITORS                   ("Creditors",                               ReportFamily.PAYABLES,         false),
    CREDITOR_PROVIDER_DETAIL    ("Creditor — provider detail",              ReportFamily.PAYABLES,         false),
    CREDITOR_MEMBER_DETAIL      ("Creditor — member detail",                ReportFamily.PAYABLES,         false),
    PAYMENT_ADVICE              ("Payment advice",                          ReportFamily.PAYABLES,         false),
    PAYMENT_ADVICE_DETAIL       ("Payment advice detail",                   ReportFamily.PAYABLES,         false),
    PAYMENT_RUNS                ("Payment runs",                            ReportFamily.PAYABLES,         false),
    PAYMENT_RUN_ITEMS           ("Payment run items",                       ReportFamily.PAYABLES,         false),
    PAYMENT_RUN_WORKBOOK        ("Payment run workbook (multi-currency)",   ReportFamily.PAYABLES,         false),
    NOTES                       ("Notes",                                   ReportFamily.PAYABLES,         false),
    NOTES_TAX_WITHHELD          ("Notes — tax withheld",                    ReportFamily.PAYABLES,         false),
    NOTES_DEBIT                 ("Notes — debit",                           ReportFamily.PAYABLES,         false),
    NOTES_CREDIT                ("Notes — credit",                          ReportFamily.PAYABLES,         false),
    NOTES_MEMO                  ("Notes — memo",                            ReportFamily.PAYABLES,         false),
    ADVANCE_PAYMENTS            ("Advance payments",                        ReportFamily.PAYABLES,         false),
    CTC_PAYMENTS                ("CTC payments",                            ReportFamily.PAYABLES,         false),
    RECONCILIATIONS             ("Bank reconciliations",                    ReportFamily.PAYABLES,         false),

    // ── Claims financial (Phase 4) ───────────────────────────────────────────
    CLAIMS_SUMMARY              ("Claims summary",                          ReportFamily.CLAIMS_FINANCIAL, true),
    CLAIM_STATUS_LIST           ("Claim status list",                       ReportFamily.CLAIMS_FINANCIAL, false),
    CLAIMS_FREQUENCY_SEVERITY   ("Claims frequency & severity",             ReportFamily.CLAIMS_FINANCIAL, false),
    DENIAL_ANALYSIS             ("Denial analysis",                         ReportFamily.CLAIMS_FINANCIAL, false),
    HIGH_COST_CLAIMANT          ("High-cost claimant",                      ReportFamily.CLAIMS_FINANCIAL, false),
    PRE_AUTH_ACTIVITY           ("Pre-auth activity",                       ReportFamily.CLAIMS_FINANCIAL, false),

    // ── Cross-service / reconciliation (Phase 5) ─────────────────────────────
    LOSS_RATIO                  ("Loss ratio (billing vs claims)",          ReportFamily.RECONCILIATION,   true),
    MEMBER_PAYMENTS_UNIFIED     ("Member payments — unified",               ReportFamily.RECONCILIATION,   false),
    PROVIDER_BALANCE_HISTORY    ("Provider balance history",                ReportFamily.RECONCILIATION,   false),
    MEMBER_BALANCE_HISTORY      ("Member balance history",                  ReportFamily.RECONCILIATION,   false),

    // ── Aged / cash-flow (Phase 8) ───────────────────────────────────────────
    CASH_FLOW_FORECAST_13W      ("Cash flow forecast (13 weeks)",           ReportFamily.RECONCILIATION,   true),
    COLLECTION_RATE_TREND       ("Collection rate trend",                   ReportFamily.RECONCILIATION,   false),

    // ── Cheap-query family (Phase 13) ────────────────────────────────────────
    POLICY_MOVEMENT             ("Policy movement",                         ReportFamily.CLAIMS_FINANCIAL, true),
    PERSISTENCY_COHORT          ("Persistency cohort",                      ReportFamily.CLAIMS_FINANCIAL, false),
    GROUP_CENSUS                ("Group census",                            ReportFamily.CLAIMS_FINANCIAL, false),
    PROVIDER_NETWORK_UTILIZATION("Provider network utilization",            ReportFamily.CLAIMS_FINANCIAL, false),

    // ── Reinsurance (Phase 10) ───────────────────────────────────────────────
    REINSURANCE_CESSION_BORDEREAU ("Reinsurance — cession bordereau",       ReportFamily.REINSURANCE,      true),
    REINSURANCE_RECOVERIES        ("Reinsurance — recoveries bordereau",    ReportFamily.REINSURANCE,      true),
    REINSURANCE_TREATY_UTILIZATION("Reinsurance — treaty utilization",      ReportFamily.REINSURANCE,      false),

    // ── Commission (Phase 11) ────────────────────────────────────────────────
    COMMISSION_STATEMENT        ("Commission statement",                    ReportFamily.COMMISSION,       true),
    COMMISSION_CLAWBACK         ("Commission clawback register",            ReportFamily.COMMISSION,       false),

    // ── Premium register (Phase 12) ──────────────────────────────────────────
    UPR_MOVEMENT                ("UPR movement",                            ReportFamily.CLAIMS_FINANCIAL, true),
    PREMIUM_REGISTER            ("Premium register",                        ReportFamily.CLAIMS_FINANCIAL, false),
    NEW_BUSINESS_REGISTER       ("New business register",                   ReportFamily.CLAIMS_FINANCIAL, false),

    // ── Actuarial (Phase 14) ─────────────────────────────────────────────────
    IBNR_TRIANGLE               ("IBNR triangle",                           ReportFamily.ACTUARIAL,        false),
    LOSS_TRIANGLE               ("Loss triangle",                           ReportFamily.ACTUARIAL,        false),
    PERSISTENCY_STUDY           ("Persistency study",                       ReportFamily.ACTUARIAL,        false),
    MORTALITY_STUDY             ("Mortality study",                         ReportFamily.ACTUARIAL,        false),
    MORBIDITY_STUDY             ("Morbidity study",                         ReportFamily.ACTUARIAL,        false),
    LAPSE_STUDY                 ("Lapse study",                             ReportFamily.ACTUARIAL,        false),

    // ── IFRS 17 (Phase 15) ───────────────────────────────────────────────────
    IFRS17_LRC_LIC_RECONCILIATION       ("IFRS 17 — LRC / LIC reconciliation",       ReportFamily.REGULATORY, true),
    IFRS17_INSURANCE_REVENUE_SERVICE_RESULT ("IFRS 17 — insurance revenue & service result", ReportFamily.REGULATORY, true),

    // ── Regulatory (Phase 16) — jurisdiction-gated on top of the toggle ──────
    IPEC_QUARTERLY_RETURN       ("IPEC — quarterly return (ZW)",            ReportFamily.REGULATORY,       true),
    CMS_ASR                     ("CMS — annual statutory return (ZA)",      ReportFamily.REGULATORY,       true),
    NAIC_SCHEDULE_P             ("NAIC Schedule P (US)",                    ReportFamily.REGULATORY,       true),
    NAIC_SCHEDULE_F             ("NAIC Schedule F (US)",                    ReportFamily.REGULATORY,       true),
    PMB_SPEND                   ("PMB spend",                               ReportFamily.REGULATORY,       true),
    AML_STR                     ("AML / STR return",                        ReportFamily.REGULATORY,       true),
    TAX_WITHHELD_RETURN         ("Tax-withheld return",                     ReportFamily.REGULATORY,       true),
    VAT_RETURN                  ("VAT return",                              ReportFamily.REGULATORY,       true),

    // ── Executive KPI (Phase 18) ─────────────────────────────────────────────
    COMBINED_RATIO              ("Combined ratio",                          ReportFamily.DASHBOARD,        false),
    LOSS_RATIO_KPI              ("Loss ratio (KPI)",                        ReportFamily.DASHBOARD,        false),
    EXPENSE_RATIO               ("Expense ratio",                           ReportFamily.DASHBOARD,        false),

    // ── Fraud (Phase 19) ─────────────────────────────────────────────────────
    FRAUD_SIU_REPORT            ("Fraud / SIU report",                      ReportFamily.FRAUD,            true);

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
