package com.medfund.shared.security;

import java.util.Set;

/**
 * Mirrors the canonical catalogue at {@code services/java/shared/src/main/resources/permissions.yaml}.
 *
 * <p>This class is the runtime source of truth for "which permissions exist" —
 * {@code RoleController} validates every key sent in {@code PUT /roles/{id}/permissions}
 * against {@link #ALL}, and rejects unknown keys with HTTP 400. That guarantees a tenant
 * admin cannot grant a permission the platform doesn't understand (no privilege
 * escalation via typos or fabricated keys).
 *
 * <p>Adding a permission requires three coordinated edits:
 * <ol>
 *   <li>Append to {@code permissions.yaml} under the right {@code domain}.</li>
 *   <li>Add a constant here and include it in {@link #ALL}.</li>
 *   <li>Add it to {@code clients/angular/src/app/core/security/permissions.ts}.</li>
 * </ol>
 *
 * <p>Domains are stable strings used by the operational portal sidebar and by
 * {@code @RequiresPermission} annotations. Renaming a key is a breaking change —
 * old role rows will reference a key that no longer exists. Prefer adding a new
 * key and migrating rows.
 */
public final class Permissions {

    private Permissions() {}

    // ── Claims ───────────────────────────────────────────────────────────────
    public static final String CLAIMS_VIEW                       = "claims:view";
    public static final String CLAIMS_CREATE                     = "claims:create";
    public static final String CLAIMS_ASSESS                     = "claims:assess";
    public static final String CLAIMS_ADJUDICATE                 = "claims:adjudicate";
    public static final String CLAIMS_REJECT                     = "claims:reject";
    public static final String CLAIMS_VERIFY                     = "claims:verify";
    public static final String CLAIMS_VIEW_DRUG                  = "claims:view_drug";
    public static final String CLAIMS_CREATE_DRUG                = "claims:create_drug";
    public static final String CLAIMS_ADJUDICATE_DRUG            = "claims:adjudicate_drug";
    public static final String CLAIMS_MANAGE_PREAUTH             = "claims:manage_preauth";
    public static final String CLAIMS_MANAGE_DRUG_PREAUTH        = "claims:manage_drug_preauth";
    public static final String CLAIMS_MANAGE_TARIFFS             = "claims:manage_tariffs";
    public static final String CLAIMS_MANAGE_MODIFIERS           = "claims:manage_modifiers";
    public static final String CLAIMS_MANAGE_REJECTION_REASONS   = "claims:manage_rejection_reasons";
    public static final String CLAIMS_MANAGE_VERIFICATION_CODES  = "claims:manage_verification_codes";
    public static final String CLAIMS_ASSIGN                     = "claims:assign";
    public static final String CLAIMS_VIEW_CTC_PAYMENTS          = "claims:view_ctc_payments";
    public static final String CLAIMS_COMMIT_CTC_PAYMENT         = "claims:commit_ctc_payment";

    // ── Billing ──────────────────────────────────────────────────────────────
    public static final String BILLING_VIEW                      = "billing:view";
    public static final String BILLING_MANAGE_SCHEMES            = "billing:manage_schemes";
    public static final String BILLING_MANAGE_AGE_GROUPS         = "billing:manage_age_groups";
    public static final String BILLING_MANAGE_WAITING_PERIODS    = "billing:manage_waiting_periods";
    public static final String BILLING_MANAGE_GROUPS             = "billing:manage_groups";
    public static final String BILLING_MANAGE_DEPENDANTS         = "billing:manage_dependants";
    public static final String BILLING_GENERATE_BILLING          = "billing:generate_billing";
    public static final String BILLING_VIEW_STATEMENTS           = "billing:view_statements";
    public static final String BILLING_POST_TRANSACTIONS         = "billing:post_transactions";
    public static final String BILLING_VIEW_CURRENCIES           = "billing:view_currencies";
    public static final String BILLING_MANAGE_CURRENCIES         = "billing:manage_currencies";
    public static final String BILLING_MANAGE_BILLING_SETTINGS   = "billing:manage_billing_settings";
    public static final String BILLING_VIEW_CREDITORS            = "billing:view_creditors";
    public static final String BILLING_MANAGE_BAD_DEBTS          = "billing:manage_bad_debts";

    // ── Finance ──────────────────────────────────────────────────────────────
    public static final String FINANCE_VIEW                      = "finance:view";
    public static final String FINANCE_CREATE_PAYMENT_RUN        = "finance:create_payment_run";
    public static final String FINANCE_APPROVE_PAYMENT_RUN       = "finance:approve_payment_run";
    public static final String FINANCE_VIEW_ADVANCE_PAYMENTS     = "finance:view_advance_payments";
    public static final String FINANCE_MANAGE_ADVANCE_PAYMENTS   = "finance:manage_advance_payments";
    public static final String FINANCE_APPROVE_ADVANCE_PAYMENT   = "finance:approve_advance_payment";
    public static final String FINANCE_REVERSE_ADVANCE_PAYMENT   = "finance:reverse_advance_payment";
    public static final String FINANCE_MANAGE_CTC_PAYMENTS       = "finance:manage_ctc_payments";
    public static final String FINANCE_REVERSE_CTC_PAYMENT       = "finance:reverse_ctc_payment";
    public static final String FINANCE_VIEW_MEMBER_PAYABLES      = "finance:view_member_payables";
    public static final String FINANCE_CONFIGURE_AUTO_CTC        = "finance:configure_auto_ctc";
    public static final String FINANCE_MANAGE_RECEIPTS           = "finance:manage_receipts";
    public static final String FINANCE_MANAGE_BANKS              = "finance:manage_banks";
    public static final String FINANCE_POST_ADJUSTMENTS          = "finance:post_adjustments";
    public static final String FINANCE_VIEW_DEBTORS              = "finance:view_debtors";
    public static final String FINANCE_VIEW_SUBLEDGER            = "finance:view_subledger";
    public static final String FINANCE_MANAGE_BILLING_RECONCILE  = "finance:manage_billing_reconcile";
    public static final String FINANCE_VIEW_PAYMENT_ADVICE       = "finance:view_payment_advice";
    public static final String FINANCE_MANAGE_COPAYMENTS         = "finance:manage_copayments";
    public static final String FINANCE_VIEW_WITHHELD_TAX         = "finance:view_withheld_tax";

    // ── Members ──────────────────────────────────────────────────────────────
    public static final String MEMBERS_VIEW                      = "members:view";
    public static final String MEMBERS_CREATE                    = "members:create";
    public static final String MEMBERS_UPDATE                    = "members:update";
    public static final String MEMBERS_DEACTIVATE                = "members:deactivate";
    public static final String MEMBERS_VIEW_DEPENDANTS           = "members:view_dependants";
    public static final String MEMBERS_MANAGE_WAIVERS            = "members:manage_waivers";
    public static final String MEMBERS_VIEW_HISTORY              = "members:view_history";

    // ── Providers ────────────────────────────────────────────────────────────
    public static final String PROVIDERS_VIEW                    = "providers:view";
    public static final String PROVIDERS_CREATE                  = "providers:create";
    public static final String PROVIDERS_UPDATE                  = "providers:update";
    public static final String PROVIDERS_MANAGE_CONTRACTS        = "providers:manage_contracts";

    // ── Tenant administration ────────────────────────────────────────────────
    public static final String ADMIN_MANAGE_ROLES                = "admin:manage_roles";
    public static final String ADMIN_MANAGE_USERS                = "admin:manage_users";
    public static final String ADMIN_VIEW_AUDIT                  = "admin:view_audit";
    public static final String ADMIN_MANAGE_SETTINGS             = "admin:manage_settings";
    public static final String ADMIN_MANAGE_RULES                = "admin:manage_rules";

    // Platform-level (super-admin) permissions. Tenant admins should never
    // hold these; they gate the cross-tenant operational tooling.
    public static final String PLATFORM_VIEW_JOBS                = "platform:view_jobs";
    public static final String PLATFORM_MANAGE_JOBS              = "platform:manage_jobs";

    /** Every key the platform recognises. Validation gate for tenant-admin role edits. */
    public static final Set<String> ALL = Set.of(
            CLAIMS_VIEW, CLAIMS_CREATE, CLAIMS_ASSESS, CLAIMS_ADJUDICATE, CLAIMS_REJECT,
            CLAIMS_VERIFY, CLAIMS_VIEW_DRUG, CLAIMS_CREATE_DRUG, CLAIMS_ADJUDICATE_DRUG,
            CLAIMS_MANAGE_PREAUTH, CLAIMS_MANAGE_DRUG_PREAUTH, CLAIMS_MANAGE_TARIFFS,
            CLAIMS_MANAGE_MODIFIERS, CLAIMS_MANAGE_REJECTION_REASONS,
            CLAIMS_MANAGE_VERIFICATION_CODES, CLAIMS_ASSIGN,
            CLAIMS_VIEW_CTC_PAYMENTS, CLAIMS_COMMIT_CTC_PAYMENT,

            BILLING_VIEW, BILLING_MANAGE_SCHEMES, BILLING_MANAGE_AGE_GROUPS,
            BILLING_MANAGE_WAITING_PERIODS, BILLING_MANAGE_GROUPS, BILLING_MANAGE_DEPENDANTS,
            BILLING_GENERATE_BILLING, BILLING_VIEW_STATEMENTS, BILLING_POST_TRANSACTIONS,
            BILLING_VIEW_CURRENCIES, BILLING_MANAGE_CURRENCIES, BILLING_MANAGE_BILLING_SETTINGS,
            BILLING_VIEW_CREDITORS, BILLING_MANAGE_BAD_DEBTS,

            FINANCE_VIEW, FINANCE_CREATE_PAYMENT_RUN, FINANCE_APPROVE_PAYMENT_RUN,
            FINANCE_VIEW_ADVANCE_PAYMENTS, FINANCE_MANAGE_ADVANCE_PAYMENTS,
            FINANCE_APPROVE_ADVANCE_PAYMENT, FINANCE_REVERSE_ADVANCE_PAYMENT,
            FINANCE_MANAGE_CTC_PAYMENTS, FINANCE_REVERSE_CTC_PAYMENT, FINANCE_VIEW_MEMBER_PAYABLES,
            FINANCE_CONFIGURE_AUTO_CTC,
            FINANCE_MANAGE_RECEIPTS, FINANCE_MANAGE_BANKS,
            FINANCE_POST_ADJUSTMENTS, FINANCE_VIEW_DEBTORS, FINANCE_VIEW_SUBLEDGER,
            FINANCE_MANAGE_BILLING_RECONCILE, FINANCE_VIEW_PAYMENT_ADVICE,
            FINANCE_MANAGE_COPAYMENTS, FINANCE_VIEW_WITHHELD_TAX,

            MEMBERS_VIEW, MEMBERS_CREATE, MEMBERS_UPDATE, MEMBERS_DEACTIVATE,
            MEMBERS_VIEW_DEPENDANTS, MEMBERS_MANAGE_WAIVERS, MEMBERS_VIEW_HISTORY,

            PROVIDERS_VIEW, PROVIDERS_CREATE, PROVIDERS_UPDATE, PROVIDERS_MANAGE_CONTRACTS,

            ADMIN_MANAGE_ROLES, ADMIN_MANAGE_USERS, ADMIN_VIEW_AUDIT,
            ADMIN_MANAGE_SETTINGS, ADMIN_MANAGE_RULES,

            PLATFORM_VIEW_JOBS, PLATFORM_MANAGE_JOBS
    );
}
