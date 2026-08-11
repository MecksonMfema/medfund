package com.medfund.shared.security;

import java.util.List;

/**
 * Runtime catalogue of permissions, with display metadata for the role-editor
 * UI. Mirrors {@code permissions.yaml} (the human-readable spec) and
 * {@link Permissions} (the typed key constants). All three must stay in sync.
 *
 * <p>The role-management endpoint serves this catalogue verbatim to the
 * frontend, which renders one accordion per domain. Adding a permission
 * requires appending here in the right domain's list (and updating the YAML
 * spec + {@link Permissions#ALL}).
 */
public final class PermissionCatalogue {

    private PermissionCatalogue() {}

    public record Permission(String key, String label, String description) {}

    public record Domain(String id, String label, List<Permission> permissions) {}

    /** Domains in display order — matches {@code permissions.yaml}. */
    public static final List<Domain> DOMAINS = List.of(
            new Domain("claims", "Claims", List.of(
                    new Permission(Permissions.CLAIMS_VIEW,                       "View claims",                       "Read access to all claims and their statuses."),
                    new Permission(Permissions.CLAIMS_CREATE,                     "Submit claims",                     "Capture new medical claims on behalf of members."),
                    new Permission(Permissions.CLAIMS_ASSESS,                     "Assess claims",                     "Review claim details and add notes — soft adjudication."),
                    new Permission(Permissions.CLAIMS_ADJUDICATE,                 "Adjudicate claims",                 "Approve or reject submitted claims (final decision)."),
                    new Permission(Permissions.CLAIMS_REJECT,                     "Reject claims",                     "Reject claims with a reason — subset of adjudicate."),
                    new Permission(Permissions.CLAIMS_VERIFY,                     "Verify claims",                     "Pre-verify claim details before adjudication."),
                    new Permission(Permissions.CLAIMS_VIEW_DRUG,                  "View drug claims",                  "Read access to pharmaceutical claims."),
                    new Permission(Permissions.CLAIMS_CREATE_DRUG,                "Submit drug claims",                "Capture pharmaceutical claims."),
                    new Permission(Permissions.CLAIMS_ADJUDICATE_DRUG,            "Adjudicate drug claims",            "Approve or reject drug claims."),
                    new Permission(Permissions.CLAIMS_MANAGE_PREAUTH,             "Manage pre-authorizations",         "Create and approve pre-authorization requests."),
                    new Permission(Permissions.CLAIMS_MANAGE_DRUG_PREAUTH,        "Manage drug pre-authorizations",    "Create and approve drug pre-authorization requests."),
                    new Permission(Permissions.CLAIMS_MANAGE_TARIFFS,             "Manage tariffs",                    "Create, edit, and delete service tariffs."),
                    new Permission(Permissions.CLAIMS_MANAGE_MODIFIERS,           "Manage tariff modifiers",           "Configure rate adjustments applied to tariffs."),
                    new Permission(Permissions.CLAIMS_MANAGE_REJECTION_REASONS,   "Manage rejection reasons",          "Configure the catalogue of rejection reasons."),
                    new Permission(Permissions.CLAIMS_MANAGE_VERIFICATION_CODES,  "Manage verification codes",         "Issue and revoke claim verification OTPs."),
                    new Permission(Permissions.CLAIMS_ASSIGN,                     "Assign claims",                     "Allocate claims to staff for assessment."),
                    new Permission(Permissions.CLAIMS_VIEW_CTC_PAYMENTS,          "View CTC payments",                 "View Claims-to-Contributions transfers (member claim payouts credited against the member's own contribution bill)."),
                    new Permission(Permissions.CLAIMS_COMMIT_CTC_PAYMENT,         "Commit CTC payments",               "Commit a Claims-to-Contributions transfer — the member's payable is applied against their contribution bill."),
                    new Permission(Permissions.CLAIMS_REQUEST_QUOTE,              "Request eligibility quote",         "Request a pre-service cost-share quote for a member.")
            )),
            new Domain("billing", "Billing", List.of(
                    new Permission(Permissions.BILLING_VIEW,                      "View billing",                      "Read access to schemes, contributions, invoices, and statements."),
                    new Permission(Permissions.BILLING_MANAGE_SCHEMES,            "Manage schemes",                    "Create, edit, and retire benefit schemes."),
                    new Permission(Permissions.BILLING_MANAGE_AGE_GROUPS,         "Manage age groups",                 "Configure age-band boundaries used by pricing rules."),
                    new Permission(Permissions.BILLING_MANAGE_WAITING_PERIODS,    "Manage waiting periods",            "Configure new-member and scheme-change waiting periods."),
                    new Permission(Permissions.BILLING_MANAGE_GROUPS,             "Manage groups",                     "Manage employer groups and their billing terms."),
                    new Permission(Permissions.BILLING_MANAGE_DEPENDANTS,         "Manage dependants",                 "Add, remove, or update member dependants."),
                    new Permission(Permissions.BILLING_GENERATE_BILLING,          "Generate billing run",              "Run periodic contribution / invoice generation."),
                    new Permission(Permissions.BILLING_VIEW_STATEMENTS,           "View statements",                   "View and export contribution statements."),
                    new Permission(Permissions.BILLING_POST_TRANSACTIONS,         "Post transactions",                 "Record contribution or invoice transactions."),
                    new Permission(Permissions.BILLING_VIEW_CURRENCIES,           "View currencies",                   "View configured currency / FX pairs."),
                    new Permission(Permissions.BILLING_MANAGE_CURRENCIES,         "Manage currencies",                 "Add or edit currency / FX pair configurations."),
                    new Permission(Permissions.BILLING_VIEW_DEBTORS,              "View debtors",                      "View outstanding balances owed by members and groups (arrears listing)."),
                    new Permission(Permissions.BILLING_MANAGE_BAD_DEBTS,          "Manage bad debts",                  "Write off receivables that cannot be collected.")
            )),
            new Domain("finance", "Finance", List.of(
                    new Permission(Permissions.FINANCE_VIEW,                      "View finance",                      "Read access to payment runs, payments, receipts, and reports."),
                    new Permission(Permissions.FINANCE_VIEW_CREDITORS,            "View creditors",                    "View the unified Creditors page — providers and members the fund owes for approved claims."),
                    new Permission(Permissions.FINANCE_CREATE_PAYMENT_RUN,        "Create payment run",                "Create a new draft batch payment run."),
                    new Permission(Permissions.FINANCE_APPROVE_PAYMENT_RUN,       "Approve payment run",               "Execute a draft payment run — disburses funds."),
                    new Permission(Permissions.FINANCE_MANAGE_PAYMENT_RUNS,       "Manage payment runs",               "Full lifecycle management of payment runs (create, approve, execute, cancel)."),
                    new Permission(Permissions.FINANCE_MANAGE_PAYMENTS,           "Manage payments",                   "Row-level payment actions — revoke an item from a run, mark paid, cancel."),
                    new Permission(Permissions.FINANCE_VIEW_ADVANCE_PAYMENTS,     "View advance payments",             "View provider prepayments."),
                    new Permission(Permissions.FINANCE_MANAGE_ADVANCE_PAYMENTS,   "Manage advance payments",           "Create, edit, or cancel provider prepayments."),
                    new Permission(Permissions.FINANCE_MANAGE_CTC_PAYMENTS,       "Manage CTC payments",               "Create or commit Claims-to-Contributions transfers from finance."),
                    new Permission(Permissions.FINANCE_REVERSE_CTC_PAYMENT,       "Reverse CTC payments",              "Post a compensating reversal for a committed Claims-to-Contributions transfer."),
                    new Permission(Permissions.FINANCE_VIEW_MEMBER_PAYABLES,      "View member payables",              "View outstanding member-payable balances (approved claim amounts routing to members)."),
                    new Permission(Permissions.FINANCE_CONFIGURE_AUTO_CTC,        "Configure auto-CTC",                "Enable and configure automatic drafting of Claims-to-Contributions transfers from qualifying member-payee claim adjudications."),
                    new Permission(Permissions.FINANCE_MANAGE_RECEIPTS,           "Manage receipts",                   "Capture and post receipts for member or group payments."),
                    new Permission(Permissions.FINANCE_POST_ADJUSTMENTS,          "Post adjustments (legacy)",         "Deprecated V074: auto-expands to the three finance.notes:* permissions on login for compat. Do not assign to new roles."),
                    new Permission(Permissions.FINANCE_NOTES_READ,                "View notes",                        "Read access to debit / credit / memo notes."),
                    new Permission(Permissions.FINANCE_NOTES_WRITE,               "Create notes",                      "Create debit, credit, or memo notes (pending status)."),
                    new Permission(Permissions.FINANCE_NOTES_APPROVE,             "Approve, apply, or reverse notes",  "Move notes through approve → apply, and post compensating reversals for applied notes."),
                    new Permission(Permissions.FINANCE_VIEW_DEBTORS,              "View debtors",                      "View aged-debtors reports."),
                    new Permission(Permissions.FINANCE_VIEW_SUBLEDGER,            "View subledger",                    "View detailed subledger journal entries."),
                    new Permission(Permissions.FINANCE_MANAGE_BILLING_RECONCILE,  "Reconcile billing to claims",       "Match billing runs against claim payments."),
                    new Permission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE,       "View payment advice",               "View payment-advice notifications sent to providers."),
                    new Permission(Permissions.FINANCE_MANAGE_COPAYMENTS,         "Manage cost-share receipts",        "Record or adjust member cost-share (copayment) receipts."),
                    new Permission(Permissions.FINANCE_VIEW_MEMBER_LIABILITIES,   "View member liabilities",           "View the fund-issued 'the member owes' ledger — one row per adjudicated claim with a cost-share balance."),
                    new Permission(Permissions.FINANCE_VIEW_WITHHELD_TAX,         "View withheld tax",                 "View tax-withheld claims and payments.")
            )),
            new Domain("members", "Members", List.of(
                    new Permission(Permissions.MEMBERS_VIEW,                      "View members",                      "Read access to the member directory."),
                    new Permission(Permissions.MEMBERS_CREATE,                    "Add members",                       "Register new members."),
                    new Permission(Permissions.MEMBERS_UPDATE,                    "Edit members",                      "Update member profile and enrollment details."),
                    new Permission(Permissions.MEMBERS_DEACTIVATE,                "Deactivate members",                "Suspend or terminate member coverage."),
                    new Permission(Permissions.MEMBERS_VIEW_DEPENDANTS,           "View dependants",                   "Read access to a member's dependants."),
                    new Permission(Permissions.MEMBERS_MANAGE_WAIVERS,            "Manage special waivers",            "Override benefit limits for individual members."),
                    new Permission(Permissions.MEMBERS_VIEW_HISTORY,              "View member history",               "View claim, payment, and contribution history for a member.")
            )),
            new Domain("providers", "Providers", List.of(
                    new Permission(Permissions.PROVIDERS_VIEW,                    "View providers",                    "Read access to the provider directory."),
                    new Permission(Permissions.PROVIDERS_CREATE,                  "Onboard providers",                 "Register new healthcare providers."),
                    new Permission(Permissions.PROVIDERS_UPDATE,                  "Edit providers",                    "Update provider profile and credentials."),
                    new Permission(Permissions.PROVIDERS_MANAGE_CONTRACTS,        "Manage provider contracts",         "Configure tariff agreements and payment terms with providers.")
            )),
            new Domain("admin", "Tenant administration", List.of(
                    new Permission(Permissions.ADMIN_MANAGE_ROLES,                "Manage roles & permissions",        "Create, edit, and assign tenant roles. Hold the keys to the kingdom."),
                    new Permission(Permissions.ADMIN_MANAGE_USERS,                "Manage staff users",                "Invite, edit, and deactivate staff users."),
                    new Permission(Permissions.ADMIN_VIEW_AUDIT,                  "View audit log",                    "Read tenant audit events."),
                    new Permission(Permissions.ADMIN_MANAGE_SETTINGS,             "Manage tenant settings",            "Edit branding, insurance lines, email templates, etc."),
                    new Permission(Permissions.ADMIN_MANAGE_RULES,                "Manage rules engine",               "Author and deploy tenant-specific business rules."),
                    new Permission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE,        "Manage bank accounts",              "Configure the tenant's own bank accounts used for outbound disbursements and inbound receipt matching.")
            )),
            new Domain("platform", "Platform administration", List.of(
                    new Permission(Permissions.PLATFORM_VIEW_JOBS,                "View scheduled jobs",               "View scheduled job configs and recent run history. Platform admins only."),
                    new Permission(Permissions.PLATFORM_MANAGE_JOBS,              "Manage scheduled jobs",             "Manually trigger jobs and edit schedules. Platform admins only.")
            ))
    );
}
