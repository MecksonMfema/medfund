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
    RECONCILIATION       ("Reconciliation"),
    ACTUARIAL            ("Actuarial"),
    REGULATORY           ("Regulatory"),
    REINSURANCE          ("Reinsurance"),
    COMMISSION           ("Commission"),
    DASHBOARD            ("Executive Dashboards"),
    FRAUD                ("Fraud / SIU");

    private final String label;
}
