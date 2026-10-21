package com.medfund.shared.report;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Top-level grouping for every report the platform surfaces. Drives the
 * hub landing page ({@code /tenant/finance/reports}) sidebar tree and the
 * tenant-admin reports settings grid.
 *
 * <p>Order below is the order the families render in both surfaces.
 */
@Getter
@RequiredArgsConstructor
public enum ReportFamily {
    BILLING              ("Billing"),
    RECEIPTS             ("Receipts"),
    PAYABLES             ("Payables & Creditors"),
    DEBTORS              ("Debtors"),
    CLAIMS_FINANCIAL     ("Claims Financial"),
    UNDERWRITING         ("Underwriting"),
    POLICY_LIFECYCLE     ("Policy lifecycle"),
    RECONCILIATION       ("Reconciliation"),
    ACTUARIAL            ("Actuarial"),
    // Phase 16 §0 REG19 split: REGULATORY now holds IFRS 17 only; the four
    // country/regulator returns from Phase 16 fan out into the three new
    // families below.
    REGULATORY           ("Regulatory"),
    PRUDENTIAL           ("Prudential Returns"),
    TAX                  ("Tax"),
    COMPLIANCE           ("Compliance"),
    REINSURANCE          ("Reinsurance"),
    COMMISSION           ("Commission"),
    DASHBOARD            ("Executive Dashboards"),
    FRAUD                ("Fraud / SIU");

    private final String label;
}
