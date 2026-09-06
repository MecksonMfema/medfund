package com.medfund.shared.sidebar;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical registry of every togglable item in the operations-portal
 * sidebar. Each value is the stable key stored in
 * {@code public.tenant_sidebar_section_config.section_key} — do not
 * rename in place. Adding a new sidebar item means adding a value
 * here, populating the item's {@code sectionKey} in the Angular
 * {@code OPERATIONAL_NAV} config, and updating the admin-grid guardrail
 * spec.
 *
 * <p>Naming convention: {@code <GROUP>_<ITEM_SLUG>}. Grouped by
 * {@link SidebarGroup}; the tenant-admin visibility grid renders one
 * section per group in enum-declaration order.
 *
 * <p>The Overview / Dashboard item is intentionally NOT enumerated
 * here — it stays permanently visible so a tenant admin who has
 * disabled everything else still has an entry point.
 */
@Getter
@RequiredArgsConstructor
public enum SidebarSectionKey {

    // Billing (10)
    BILLING_TRANSACTIONS           ("Transactions",           SidebarGroup.BILLING),
    BILLING_RECORD_TRANSACTION     ("Record Transaction",     SidebarGroup.BILLING),
    BILLING_SCHEMES                ("Schemes",                SidebarGroup.BILLING),
    BILLING_AGE_GROUPS             ("Age Groups",             SidebarGroup.BILLING),
    BILLING_GENERATE               ("Generate",               SidebarGroup.BILLING),
    BILLING_CONTRIBUTION_STATEMENTS("Contribution Statements",SidebarGroup.BILLING),
    BILLING_LEDGER                 ("Ledger",                 SidebarGroup.BILLING),
    BILLING_DEBTORS                ("Debtors",                SidebarGroup.BILLING),
    BILLING_BAD_DEBTS              ("Bad Debts",              SidebarGroup.BILLING),
    BILLING_CHARGE_PREVIEW         ("Charge Preview",         SidebarGroup.BILLING),

    // Policies (6)
    POLICIES_VEHICLES              ("Vehicles",               SidebarGroup.POLICIES),
    POLICIES_PROPERTIES            ("Properties",             SidebarGroup.POLICIES),
    POLICIES_LIFE                  ("Life",                   SidebarGroup.POLICIES),
    POLICIES_FUNERAL               ("Funeral",                SidebarGroup.POLICIES),
    POLICIES_TRAVEL                ("Travel",                 SidebarGroup.POLICIES),
    POLICIES_DISABILITY            ("Disability",             SidebarGroup.POLICIES),

    // Members (2)
    MEMBERS_MEMBERS                ("Members",                SidebarGroup.MEMBERS),
    MEMBERS_GROUPS                 ("Groups",                 SidebarGroup.MEMBERS),

    // Claims (7)
    CLAIMS_ALL                     ("All Claims",             SidebarGroup.CLAIMS),
    CLAIMS_SUBMIT                  ("Submit Claim",           SidebarGroup.CLAIMS),
    CLAIMS_ELIGIBILITY_QUOTE       ("Eligibility Quote",      SidebarGroup.CLAIMS),
    CLAIMS_PREAUTH                 ("Pre-Authorizations",     SidebarGroup.CLAIMS),
    CLAIMS_NEW_PREAUTH             ("New Pre-Auth",           SidebarGroup.CLAIMS),
    CLAIMS_TARIFFS                 ("Tariffs",                SidebarGroup.CLAIMS),
    CLAIMS_SIU_CASES               ("SIU Cases",              SidebarGroup.CLAIMS),

    // Finance (9)
    FINANCE_PAYMENT_RUNS           ("Payment Runs",           SidebarGroup.FINANCE),
    FINANCE_ADVANCE_PAYMENTS       ("Advance Payments",       SidebarGroup.FINANCE),
    FINANCE_CTC_PAYMENTS           ("CTC Payments",           SidebarGroup.FINANCE),
    FINANCE_CREDITORS              ("Creditors",              SidebarGroup.FINANCE),
    FINANCE_RECONCILIATION         ("Reconciliation",         SidebarGroup.FINANCE),
    FINANCE_NOTES                  ("Notes",                  SidebarGroup.FINANCE),
    FINANCE_PAYMENT_ADVICE         ("Payment Advice",         SidebarGroup.FINANCE),
    FINANCE_COST_SHARE_RECEIPTS    ("Cost-share receipts",    SidebarGroup.FINANCE),
    FINANCE_MEMBER_LIABILITIES     ("Member Liabilities",     SidebarGroup.FINANCE),

    // Reporting (7)
    // Facultative Browse + Queue were merged into a single Facultative
    // page (V180 drops the deprecated section_key rows).
    // Adjustments (draft) + (approve) were merged and renamed to
    // Commission corrections (V180 drops those rows too).
    REPORTING_FACULTATIVE              ("Facultative reinsurance",    SidebarGroup.REPORTING),
    REPORTING_REINSURANCE_REVIEW_QUEUE ("Reinsurance - Review Queue", SidebarGroup.REPORTING),
    REPORTING_PRODUCER_PAYOUTS         ("Producer Payouts",           SidebarGroup.REPORTING),
    REPORTING_COMMISSION_CORRECTIONS   ("Commission corrections",     SidebarGroup.REPORTING),
    REPORTING_ENDORSEMENT_REVIEW       ("Endorsement Review",         SidebarGroup.REPORTING),
    REPORTING_EXECUTIVE_KPIS           ("Executive KPIs",             SidebarGroup.REPORTING),
    REPORTING_REPORTS                  ("Reports",                    SidebarGroup.REPORTING);

    /** Human-readable label — displayed in the tenant-admin visibility grid. */
    private final String label;

    /** Group bucket — controls the section heading in the visibility grid. */
    private final SidebarGroup group;

    /** Wire-shape key persisted in {@code tenant_sidebar_section_config.section_key}. */
    public String key() {
        return name();
    }

    /** Safe parse — returns {@link Optional#empty()} on an unknown key. */
    public static Optional<SidebarSectionKey> parse(String key) {
        if (key == null) return Optional.empty();
        String upper = key.toUpperCase();
        return Arrays.stream(values()).filter(k -> k.name().equals(upper)).findFirst();
    }
}
