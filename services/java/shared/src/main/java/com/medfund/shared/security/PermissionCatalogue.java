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

    /** Domains in display order - matches {@code permissions.yaml}. */
    public static final List<Domain> DOMAINS = List.of(
            new Domain("claims", "Claims", List.of(
                    new Permission(Permissions.CLAIMS_VIEW,                       "View claims",                       "Read access to all claims and their statuses."),
                    new Permission(Permissions.CLAIMS_CREATE,                     "Submit claims",                     "Capture new medical claims on behalf of members."),
                    new Permission(Permissions.CLAIMS_ASSESS,                     "Assess claims",                     "Review claim details and add notes - soft adjudication."),
                    new Permission(Permissions.CLAIMS_ADJUDICATE,                 "Adjudicate claims",                 "Approve or reject submitted claims (final decision)."),
                    new Permission(Permissions.CLAIMS_REJECT,                     "Reject claims",                     "Reject claims with a reason - subset of adjudicate."),
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
                    new Permission(Permissions.CLAIMS_COMMIT_CTC_PAYMENT,         "Commit CTC payments",               "Commit a Claims-to-Contributions transfer - the member's payable is applied against their contribution bill."),
                    new Permission(Permissions.CLAIMS_REQUEST_QUOTE,              "Request eligibility quote",         "Request a pre-service cost-share quote for a member."),
                    new Permission(Permissions.CLAIMS_SET_RESERVE,                "Set claim case reserve",            "Set or update the case reserve on a claim (actuarial IBNR incurred-triangle input). Decoupled from adjudicate so tenants can grant reserve-setting to a supervisor role only."),
                    // SIU (Special Investigations Unit) sub-namespace.
                    new Permission(Permissions.CLAIMS_SIU_VIEW,                   "View SIU cases",                    "Read access to the SIU case queue and case-detail page."),
                    new Permission(Permissions.CLAIMS_SIU_CREATE,                 "Open SIU case",                     "Manually open an SIU investigation for a claim, independent of the AI FRAUD_TRIAGE auto-open path."),
                    new Permission(Permissions.CLAIMS_SIU_INVESTIGATE,            "Investigate SIU case",              "Transition an SIU case through the investigation workflow: start review, add notes, close CONFIRMED / DISMISSED, upload evidence."),
                    new Permission(Permissions.CLAIMS_SIU_ADMIN,                  "Administer SIU cases",              "Elevated SIU operations: delete evidence, override state, unlock investigator queues."),
                    new Permission(Permissions.CLAIMS_SIU_ASSIGN,                 "Assign SIU cases",                  "Assign or reassign SIU cases to specific investigators."),
                    new Permission(Permissions.CLAIMS_SIU_APPROVE,                "Approve SIU case closure",          "Four-eyes approval of investigator-proposed CONFIRMED closures (supervisor role)."),
                    new Permission(Permissions.CLAIMS_SIU_REOPEN,                 "Reopen closed SIU cases",           "Reopen a closed SIU case back to UNDER_REVIEW."),
                    new Permission(Permissions.CLAIMS_SIU_REFER,                  "Refer SIU cases externally",        "Record referrals to law enforcement, regulator, or internal HR.")
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
                    new Permission(Permissions.BILLING_MANAGE_BILLING_SETTINGS,   "Manage billing settings",           "Edit benefit-type, payment-method, transaction-type catalogues plus dunning and cycle configuration."),
                    new Permission(Permissions.BILLING_VIEW_DEBTORS,              "View debtors",                      "View outstanding balances owed by members and groups (arrears listing)."),
                    new Permission(Permissions.BILLING_MANAGE_BAD_DEBTS,          "Manage bad debts",                  "Write off receivables that cannot be collected.")
            )),
            new Domain("finance", "Finance", List.of(
                    new Permission(Permissions.FINANCE_VIEW,                      "View finance",                      "Read access to payment runs, payments, receipts, and reports."),
                    new Permission(Permissions.FINANCE_VIEW_CREDITORS,            "View creditors",                    "View the unified Creditors page - providers and members the fund owes for approved claims."),
                    new Permission(Permissions.FINANCE_CREATE_PAYMENT_RUN,        "Create payment run",                "Create a new draft batch payment run."),
                    new Permission(Permissions.FINANCE_APPROVE_PAYMENT_RUN,       "Approve payment run",               "Execute a draft payment run - disburses funds."),
                    new Permission(Permissions.FINANCE_MANAGE_PAYMENT_RUNS,       "Manage payment runs",               "Full lifecycle management of payment runs (create, approve, execute, cancel)."),
                    new Permission(Permissions.FINANCE_MANAGE_PAYMENTS,           "Manage payments",                   "Row-level payment actions: revoke an item from a run, mark paid, cancel."),
                    new Permission(Permissions.FINANCE_VIEW_ADVANCE_PAYMENTS,     "View advance payments",             "View provider prepayments."),
                    new Permission(Permissions.FINANCE_MANAGE_ADVANCE_PAYMENTS,   "Manage advance payments",           "Create, edit, or cancel provider prepayments."),
                    new Permission(Permissions.FINANCE_APPROVE_ADVANCE_PAYMENT,   "Approve advance payment",           "Approve a pending advance payment above the tenant threshold. Approver must differ from the recorder."),
                    new Permission(Permissions.FINANCE_REVERSE_ADVANCE_PAYMENT,   "Reverse advance payment",           "Post a compensating reversal for an approved or applied advance payment."),
                    new Permission(Permissions.FINANCE_MANAGE_CTC_PAYMENTS,       "Manage CTC payments",               "Create or commit Claims-to-Contributions transfers from finance."),
                    new Permission(Permissions.FINANCE_REVERSE_CTC_PAYMENT,       "Reverse CTC payments",              "Post a compensating reversal for a committed Claims-to-Contributions transfer."),
                    new Permission(Permissions.FINANCE_VIEW_MEMBER_PAYABLES,      "View member payables",              "View outstanding member-payable balances (approved claim amounts routing to members)."),
                    new Permission(Permissions.FINANCE_CONFIGURE_AUTO_CTC,        "Configure auto-CTC",                "Enable and configure automatic drafting of Claims-to-Contributions transfers from qualifying member-payee claim adjudications."),
                    new Permission(Permissions.FINANCE_MANAGE_RECEIPTS,           "Manage receipts",                   "Capture and post receipts for member or group payments."),
                    new Permission(Permissions.FINANCE_POST_ADJUSTMENTS,          "Post adjustments (legacy)",         "Deprecated V074: auto-expands to the three finance.notes:* permissions on login for compat. Do not assign to new roles."),
                    new Permission(Permissions.FINANCE_NOTES_READ,                "View notes",                        "Read access to debit / credit / memo notes."),
                    new Permission(Permissions.FINANCE_NOTES_WRITE,               "Create notes",                      "Create debit, credit, or memo notes (pending status)."),
                    new Permission(Permissions.FINANCE_NOTES_APPROVE,             "Approve, apply, or reverse notes",  "Move notes through approve to applied, and post compensating reversals for applied notes."),
                    new Permission(Permissions.FINANCE_VIEW_DEBTORS,              "View debtors",                      "View aged-debtors reports."),
                    new Permission(Permissions.FINANCE_VIEW_SUBLEDGER,            "View subledger",                    "View detailed subledger journal entries."),
                    new Permission(Permissions.FINANCE_MANAGE_BILLING_RECONCILE,  "Reconcile billing to claims",       "Match billing runs against claim payments."),
                    new Permission(Permissions.FINANCE_VIEW_PAYMENT_ADVICE,       "View payment advice",               "View payment-advice notifications sent to providers."),
                    new Permission(Permissions.FINANCE_GENERATE_PAYMENT_ADVICE,   "Generate payment advice",           "Manually generate or regenerate the per-payee payment advice ledger for a run."),
                    new Permission(Permissions.FINANCE_MANAGE_COPAYMENTS,         "Manage cost-share receipts",        "Record or adjust member cost-share (copayment) receipts."),
                    new Permission(Permissions.FINANCE_VIEW_MEMBER_LIABILITIES,   "View member liabilities",           "View the fund-issued 'the member owes' ledger: one row per adjudicated claim with a cost-share balance."),
                    new Permission(Permissions.FINANCE_VIEW_WITHHELD_TAX,         "View withheld tax",                 "View tax-withheld claims and payments."),
                    new Permission(Permissions.FINANCE_EXPORT_REGULATORY,         "Export regulator returns",          "Submit a regulator-format return (IPEC quarterly, CMS ASR, NAIC Schedule P/F, PMB spend, VAT, tax-withheld, AML/STR) and download the composed XLSX."),
                    // Reinsurance sub-namespace.
                    new Permission(Permissions.REINSURANCE_VIEW,                     "View reinsurance",              "Read access to reinsurers, treaties, cessions, and recoveries."),
                    new Permission(Permissions.REINSURANCE_MANAGE_TREATY,            "Manage treaties",               "Full CRUD on reinsurers and treaties including draft creation and activation."),
                    new Permission(Permissions.REINSURANCE_CEDE_FACULTATIVE,         "Cede facultative",              "Create DRAFT facultative cessions against an active treaty."),
                    new Permission(Permissions.REINSURANCE_APPROVE_FACULTATIVE,      "Approve facultative",           "Move facultative cessions DRAFT to APPROVED to CEDED and void pre-terminal facultatives."),
                    new Permission(Permissions.REINSURANCE_RECORD_RECOVERY_RECEIVED, "Record recovery received",      "Record the recovery amount received from a reinsurer against an invoiced expectation."),
                    new Permission(Permissions.REINSURANCE_WRITEOFF_RECOVERY,        "Write off recovery",            "Write off an uncollectable reinsurance recovery with a stated reason."),
                    new Permission(Permissions.REINSURANCE_RESOLVE_REVIEW,           "Resolve reinsurance review",    "Resolve tasks in the reinsurance review queue (claim regression, recovery dispute, manual void requests)."),
                    // Producer sub-namespace.
                    new Permission(Permissions.PRODUCER_VIEW,                        "View producers",                "Read access to producers, hierarchy, and their member assignments."),
                    new Permission(Permissions.PRODUCER_MANAGE,                      "Manage producers",              "Create and update producers, rate cards, and member-to-producer assignments."),
                    new Permission(Permissions.PRODUCER_TERMINATE,                   "Terminate producers",           "Terminate a producer and bulk-reassign their open member assignments."),
                    new Permission(Permissions.PRODUCER_BACKFILL_REVIEW,             "Review producer backfill",      "Kick off the treaty.producer_ref to producer_id backfill job and accept/reject fuzzy-match candidates."),
                    // Commission sub-namespace.
                    new Permission(Permissions.COMMISSION_VIEW,                      "View commission",               "Read access to commission transactions, clawback register, and adjustment queues."),
                    new Permission(Permissions.COMMISSION_MANAGE_RATE_CARD,          "Manage rate cards",             "Create, update, and deactivate commission rate cards."),
                    new Permission(Permissions.COMMISSION_DRAFT_ADJUSTMENT,          "Draft commission adjustment",   "Create DRAFT commission adjustments (four-eyes drafter half)."),
                    new Permission(Permissions.COMMISSION_APPROVE_ADJUSTMENT,        "Approve commission adjustment", "Move commission adjustments DRAFT to APPROVED to COMMITTED and void pre-terminal adjustments."),
                    new Permission(Permissions.COMMISSION_CREATE_PAYOUT_RUN,         "Create commission payout run",  "Create producer-payee PaymentRuns aggregating ACCRUED commissions in the period."),
                    new Permission(Permissions.COMMISSION_APPROVE_PAYOUT_RUN,        "Approve commission payout run", "Approve and execute producer payout runs - disburses commission funds.")
            )),
            new Domain("members", "Members", List.of(
                    new Permission(Permissions.MEMBERS_VIEW,                      "View members",                      "Read access to the member directory."),
                    new Permission(Permissions.MEMBERS_CREATE,                    "Add members",                       "Register new members."),
                    new Permission(Permissions.MEMBERS_UPDATE,                    "Edit members",                      "Update member profile and enrollment details."),
                    new Permission(Permissions.MEMBERS_DEACTIVATE,                "Deactivate members",                "Suspend or terminate member coverage."),
                    new Permission(Permissions.MEMBERS_VIEW_DEPENDANTS,           "View dependants",                   "Read access to a member's dependants."),
                    new Permission(Permissions.MEMBERS_MANAGE_WAIVERS,            "Manage special waivers",            "Override benefit limits for individual members."),
                    new Permission(Permissions.MEMBERS_VIEW_HISTORY,              "View member history",               "View claim, payment, and contribution history for a member."),
                    new Permission(Permissions.MEMBERS_RECORD_DEATH,              "Record member death",               "Record a member's death (date + optional ICD-10 chapter or cause note); status flips to 'deceased' via the status transition service and feeds the MORTALITY_STUDY actuarial report.")
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
            new Domain("tenant", "Tenant settings", List.of(
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_AUTO_LAPSE,             "Manage auto-lapse settings",          "Enable/disable the auto-lapse chain and configure arrears-threshold-months + grace-window-days."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_ENDORSEMENT_CONFIG,     "Manage endorsement four-eyes gate",   "Enable/disable the endorsement four-eyes threshold and configure its amount + currency."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_ACTUARIAL_BASES,        "Manage actuarial basis tables",       "Add, edit, or delete per-tenant persistency / mortality / morbidity basis rows consumed by the actuarial studies."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_IFRS17_CONFIG,          "Manage IFRS 17 admin config",         "Add, edit, or delete per-tenant IFRS 17 admin surfaces: risk adjustment methodology, yield curves and expense assumptions."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_REGULATORY_TEMPLATES,   "Manage regulator XLSX templates",     "Upload / delete tenant-side overrides of the bundled regulator XLSX templates (IPEC, CMS, NAIC, PMB, VAT, tax-withheld, AML) consumed by the regulatory report exporter."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_REGULATORY_NOTIFICATIONS, "Manage regulator due-date recipients", "Add, edit, or delete the tenant's email recipient list for the RegulatoryDueDateScanner reminders (7d / 1d / due / overdue)."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_NAIC_CONFIG,            "Manage NAIC company identity",        "Manage the per-tenant US NAIC identity (state of domicile, company code, group code, FEIN) consumed by the NAIC Schedule P/F shapers. US-only surface."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_TAX_CONFIG,             "Manage tenant tax rates",             "Manage per-tenant statutory tax rates (VAT + Withholding) per transaction category by currency, consumed by the VAT Return + TaxWithheldReturn shapers."),
                    new Permission(Permissions.TENANT_SETTINGS_MANAGE_REPORT_SCHEDULES,       "Manage scheduled report delivery",    "Create, edit, delete, and re-run scheduled report delivery configurations and their recipient lists. Also grants access to the schedule run history."),
                    new Permission(Permissions.TENANT_SETTINGS_TENANT_ADMIN,                  "Tenant admin (privileged actions)",   "Umbrella capability for privileged tenant-scoped actions such as the test-only ScheduledReportProbe force-fire endpoint. Grant sparingly.")
            )),
            new Domain("underwriting", "Underwriting", List.of(
                    new Permission(Permissions.UNDERWRITING_PORTFOLIO_MANAGE,     "Manage IFRS 17 portfolios",         "Create, update, and soft-delete IFRS 17 portfolios used by underwriting reports."),
                    new Permission(Permissions.UNDERWRITING_COHORT_MANAGE,        "Manage IFRS 17 cohorts",            "Create, update, and soft-delete IFRS 17 cohorts (portfolio + year + type)."),
                    new Permission(Permissions.UNDERWRITING_OPENING_BALANCE_MANAGE, "Manage IFRS 17 opening balance seeds", "Add, edit, or delete tenant-admin overrides on auto-derived IFRS 17 LRC / LIC opening balances."),
                    new Permission(Permissions.UNDERWRITING_FUND_MANAGE,          "Manage VFA unit-linked funds",      "Add, edit, or delete VFA unit-linked funds, append NAV history rows, manage variable fee schedules and append policy unit ledger rows."),
                    new Permission(Permissions.PREMIUM_EARNING_MANAGE_BACKFILL,   "Trigger earning-schedule backfill", "Trigger a targeted earning-schedule backfill or replay for a single policy."),
                    new Permission(Permissions.PREMIUM_EARNING_VIEW_DEBUG,        "View earning-schedule debug rows",  "Dev-only: read raw earning_schedule rows for a policy. Not intended for production tenant admins."),
                    new Permission(Permissions.POLICY_DRAFT_ENDORSEMENT,          "Draft policy endorsement",          "Create a DRAFT policy endorsement and read the queue. Auto-commits below the four-eyes threshold."),
                    new Permission(Permissions.POLICY_APPROVE_ENDORSEMENT,        "Approve policy endorsement",        "Approve, commit, void, or mark-computed a policy endorsement. Four-eyes counterpart to draft_endorsement."),
                    new Permission(Permissions.POLICY_STATUS_MANAGE,              "Manage policy status",              "Lapse, terminate, suspend, or reinstate an annual-bind policy.")
            )),
            new Domain("compliance", "Compliance", List.of(
                    new Permission(Permissions.COMPLIANCE_AML_RAISE,              "Raise AML/STR alert",               "Raise a Suspicious Transaction Alert against a transaction. Front-line finance / claims staff typically hold this."),
                    new Permission(Permissions.COMPLIANCE_AML_REVIEW,             "Review AML/STR alerts",             "Read the AML alert queue and move RAISED alerts to REVIEWED with a triage note. Compliance officer role."),
                    new Permission(Permissions.COMPLIANCE_AML_FILE,               "File AML/STR alerts with regulator", "Move a REVIEWED alert to FILED, recording the FIU/FIC/FinCEN filing reference. Compliance lead role."),
                    new Permission(Permissions.COMPLIANCE_AML_CLOSE,              "Close AML/STR alerts",              "Close a RAISED or REVIEWED alert as not-reportable with a mandatory reason."),
                    new Permission(Permissions.COMPLIANCE_AML_CONFIGURE_THRESHOLDS, "Manage AML reporting thresholds", "Add, edit, or delete per-tenant AML reporting thresholds per transaction type by currency. Consumed by the AML/STR periodic summary calculator.")
            )),
            new Domain("platform", "Platform administration", List.of(
                    new Permission(Permissions.PLATFORM_VIEW_JOBS,                "View scheduled jobs",               "View scheduled job configs and recent run history. Platform admins only."),
                    new Permission(Permissions.PLATFORM_MANAGE_JOBS,              "Manage scheduled jobs",             "Manually trigger jobs and edit schedules. Platform admins only."),
                    new Permission(Permissions.SCHEDULED_REPORT_RENDER,           "Render scheduled report (M2M)",     "Internal service-to-service permission held by finance-service's M2M client so it can call each owner service's scheduled-render endpoint. Never grant to a human role."),
                    new Permission(Permissions.CONTRIBUTIONS_READ_AGGREGATE,      "Read contributions aggregate (M2M)", "Internal service-to-service permission held by finance-service's M2M client so it can call contributions-service's cross-service aggregate feeds. Never grant to a human role."),
                    new Permission(Permissions.CLAIMS_READ_AGGREGATE,             "Read claims aggregate (M2M)",       "Internal service-to-service permission held by finance-service's M2M client so it can call claims-service's cross-service aggregate feeds. Never grant to a human role.")
            ))
    );
}
