/**
 * Mirrors the canonical catalogue at
 * `services/java/shared/src/main/resources/permissions.yaml` (and
 * `Permissions.java`). The grouped `PERMISSION_CATALOGUE` drives the
 * role-editor UI (one accordion per domain) and `ALL_PERMISSIONS` is the
 * type-narrowing union used everywhere else.
 *
 * Adding a permission requires three coordinated edits — see the YAML header
 * for the full procedure. Rename a key only by adding the new one and
 * migrating role rows; renaming in place silently breaks existing roles.
 */

export type PermissionKey =
  // Claims
  | 'claims:view' | 'claims:create' | 'claims:assess' | 'claims:adjudicate'
  | 'claims:reject' | 'claims:verify'
  | 'claims:view_drug' | 'claims:create_drug' | 'claims:adjudicate_drug'
  | 'claims:manage_preauth' | 'claims:manage_drug_preauth'
  | 'claims:manage_tariffs' | 'claims:manage_modifiers'
  | 'claims:manage_rejection_reasons' | 'claims:manage_verification_codes'
  | 'claims:assign'
  | 'claims:view_ctc_payments' | 'claims:commit_ctc_payment'
  | 'claims:request_quote'
  // Billing
  | 'billing:view' | 'billing:manage_schemes' | 'billing:manage_age_groups'
  | 'billing:manage_waiting_periods' | 'billing:manage_groups'
  | 'billing:manage_dependants' | 'billing:generate_billing'
  | 'billing:revoke_billing'
  | 'billing:view_statements' | 'billing:post_transactions'
  | 'billing:view_currencies' | 'billing:manage_currencies'
  | 'billing:manage_billing_settings'
  | 'billing:view_debtors' | 'billing:manage_bad_debts'
  // Finance
  | 'finance:view' | 'finance:view_creditors'
  | 'finance:create_payment_run' | 'finance:approve_payment_run'
  | 'finance:manage_payment_runs' | 'finance:manage_payments'
  | 'finance:view_advance_payments' | 'finance:manage_advance_payments'
  | 'finance:approve_advance_payment' | 'finance:reverse_advance_payment'
  | 'finance:manage_ctc_payments' | 'finance:reverse_ctc_payment'
  | 'finance:view_member_payables' | 'finance:configure_auto_ctc'
  | 'finance:manage_receipts'
  | 'finance:post_adjustments'
  | 'finance.notes:read' | 'finance.notes:write' | 'finance.notes:approve'
  | 'finance:view_debtors' | 'finance:view_subledger'
  | 'finance:manage_billing_reconcile' | 'finance:view_payment_advice'
  | 'finance:manage_copayments' | 'finance:view_member_liabilities' | 'finance:view_withheld_tax'
  // Reinsurance (finance sub-namespace)
  | 'finance.reinsurance:view' | 'finance.reinsurance:manage_treaty'
  | 'finance.reinsurance:cede_facultative' | 'finance.reinsurance:approve_facultative'
  | 'finance.reinsurance:record_recovery_received'
  | 'finance.reinsurance:writeoff_recovery' | 'finance.reinsurance:resolve_review'
  // Members
  | 'members:view' | 'members:create' | 'members:update' | 'members:deactivate'
  | 'members:view_dependants' | 'members:manage_waivers' | 'members:view_history'
  // Providers
  | 'providers:view' | 'providers:create' | 'providers:update'
  | 'providers:manage_contracts'
  // Tenant administration
  | 'admin:manage_roles' | 'admin:manage_users' | 'admin:view_audit'
  | 'admin:manage_settings' | 'admin:manage_rules'
  | 'admin.bank_accounts:manage'
  // Platform administration (super-admin only)
  | 'platform:view_jobs' | 'platform:manage_jobs';

export interface PermissionDescriptor {
  key: PermissionKey;
  label: string;
  description: string;
}

export interface PermissionDomain {
  id: 'claims' | 'billing' | 'finance' | 'members' | 'providers' | 'admin' | 'platform';
  label: string;
  permissions: PermissionDescriptor[];
}

export const PERMISSION_CATALOGUE: PermissionDomain[] = [
  {
    id: 'claims',
    label: 'Claims',
    permissions: [
      { key: 'claims:view',                      label: 'View claims',                      description: 'Read access to all claims and their statuses.' },
      { key: 'claims:create',                    label: 'Submit claims',                    description: 'Capture new medical claims on behalf of members.' },
      { key: 'claims:assess',                    label: 'Assess claims',                    description: 'Review claim details and add notes — soft adjudication.' },
      { key: 'claims:adjudicate',                label: 'Adjudicate claims',                description: 'Approve or reject submitted claims (final decision).' },
      { key: 'claims:reject',                    label: 'Reject claims',                    description: 'Reject claims with a reason — subset of adjudicate.' },
      { key: 'claims:verify',                    label: 'Verify claims',                    description: 'Pre-verify claim details before adjudication.' },
      { key: 'claims:view_drug',                 label: 'View drug claims',                 description: 'Read access to pharmaceutical claims.' },
      { key: 'claims:create_drug',               label: 'Submit drug claims',               description: 'Capture pharmaceutical claims.' },
      { key: 'claims:adjudicate_drug',           label: 'Adjudicate drug claims',           description: 'Approve or reject drug claims.' },
      { key: 'claims:manage_preauth',            label: 'Manage pre-authorizations',        description: 'Create and approve pre-authorization requests.' },
      { key: 'claims:manage_drug_preauth',       label: 'Manage drug pre-authorizations',   description: 'Create and approve drug pre-authorization requests.' },
      { key: 'claims:manage_tariffs',            label: 'Manage tariffs',                   description: 'Create, edit, and delete service tariffs.' },
      { key: 'claims:manage_modifiers',          label: 'Manage tariff modifiers',          description: 'Configure rate adjustments applied to tariffs.' },
      { key: 'claims:manage_rejection_reasons', label: 'Manage rejection reasons',         description: 'Configure the catalogue of rejection reasons.' },
      { key: 'claims:manage_verification_codes', label: 'Manage verification codes',       description: 'Issue and revoke claim verification OTPs.' },
      { key: 'claims:assign',                    label: 'Assign claims',                    description: 'Allocate claims to staff for assessment.' },
      { key: 'claims:view_ctc_payments',         label: 'View CTC payments',                description: "View Claims-to-Contributions transfers (member claim payouts credited against the member's own contribution bill)." },
      { key: 'claims:commit_ctc_payment',        label: 'Commit CTC payments',              description: "Commit a Claims-to-Contributions transfer — the member's payable is applied against their contribution bill." },
      { key: 'claims:request_quote',             label: 'Request eligibility quote',        description: 'Request a pre-service cost-share quote for a member.' },
    ],
  },
  {
    id: 'billing',
    label: 'Billing',
    permissions: [
      { key: 'billing:view',                     label: 'View billing',                     description: 'Read access to schemes, contributions, invoices, and statements.' },
      { key: 'billing:manage_schemes',           label: 'Manage schemes',                   description: 'Create, edit, and retire benefit schemes.' },
      { key: 'billing:manage_age_groups',        label: 'Manage age groups',                description: 'Configure age-band boundaries used by pricing rules.' },
      { key: 'billing:manage_waiting_periods',   label: 'Manage waiting periods',           description: 'Configure new-member and scheme-change waiting periods.' },
      { key: 'billing:manage_groups',            label: 'Manage groups',                    description: 'Manage employer groups and their billing terms.' },
      { key: 'billing:manage_dependants',        label: 'Manage dependants',                description: 'Add, remove, or update member dependants.' },
      { key: 'billing:generate_billing',         label: 'Generate billing run',             description: 'Run periodic contribution / invoice generation.' },
      { key: 'billing:view_statements',          label: 'View statements',                  description: 'View and export contribution statements.' },
      { key: 'billing:post_transactions',        label: 'Post transactions',                description: 'Record contribution or invoice transactions.' },
      { key: 'billing:view_currencies',          label: 'View currencies',                  description: 'View configured currency / FX pairs.' },
      { key: 'billing:manage_currencies',        label: 'Manage currencies',                description: 'Add or edit currency / FX pair configurations.' },
      { key: 'billing:manage_billing_settings',  label: 'Manage billing settings',          description: 'Edit benefit-type, payment-method, transaction-type catalogues plus dunning and cycle configuration.' },
      { key: 'billing:view_debtors',             label: 'View debtors',                     description: 'View outstanding balances owed by members and groups (arrears listing).' },
      { key: 'billing:manage_bad_debts',         label: 'Manage bad debts',                 description: 'Write off receivables that cannot be collected.' },
    ],
  },
  {
    id: 'finance',
    label: 'Finance',
    permissions: [
      { key: 'finance:view',                     label: 'View finance',                     description: 'Read access to payment runs, payments, receipts, and reports.' },
      { key: 'finance:view_creditors',           label: 'View creditors',                   description: 'View the unified Creditors page — providers and members the fund owes for approved claims.' },
      { key: 'finance:create_payment_run',       label: 'Create payment run',               description: 'Create a new draft batch payment run.' },
      { key: 'finance:approve_payment_run',      label: 'Approve payment run',              description: 'Execute a draft payment run — disburses funds.' },
      { key: 'finance:manage_payment_runs',      label: 'Manage payment runs',              description: 'Full lifecycle management of payment runs (create, approve, execute, cancel).' },
      { key: 'finance:manage_payments',          label: 'Manage payments',                  description: 'Row-level payment actions — revoke an item from a run, mark paid, cancel.' },
      { key: 'finance:view_advance_payments',    label: 'View advance payments',            description: 'View provider prepayments.' },
      { key: 'finance:manage_advance_payments',  label: 'Manage advance payments',          description: 'Create, edit, or cancel provider prepayments.' },
      { key: 'finance:approve_advance_payment',  label: 'Approve advance payment',          description: 'Approve a pending advance payment above the tenant threshold. Approver must differ from the recorder.' },
      { key: 'finance:reverse_advance_payment',  label: 'Reverse advance payment',          description: 'Post a compensating reversal for an approved or applied advance payment.' },
      { key: 'finance:manage_ctc_payments',      label: 'Manage CTC payments',              description: 'Create or commit Claims-to-Contributions transfers from finance.' },
      { key: 'finance:reverse_ctc_payment',      label: 'Reverse CTC payments',             description: 'Post a compensating reversal for a committed Claims-to-Contributions transfer.' },
      { key: 'finance:view_member_payables',     label: 'View member payables',             description: 'View outstanding member-payable balances (approved claim amounts routing to members).' },
      { key: 'finance:configure_auto_ctc',       label: 'Configure auto-CTC',               description: 'Enable and configure automatic drafting of Claims-to-Contributions transfers from qualifying member-payee claim adjudications.' },
      { key: 'finance:manage_receipts',          label: 'Manage receipts',                  description: 'Capture and post receipts for member or group payments.' },
      { key: 'finance:post_adjustments',         label: 'Post adjustments (legacy)',        description: 'Deprecated V074: auto-expands to the three finance.notes:* permissions on login for compat. Do not assign to new roles.' },
      { key: 'finance.notes:read',               label: 'View notes',                       description: 'Read access to debit / credit / memo notes.' },
      { key: 'finance.notes:write',              label: 'Create notes',                     description: 'Create debit, credit, or memo notes (pending status).' },
      { key: 'finance.notes:approve',            label: 'Approve, apply, or reverse notes', description: 'Move notes through approve → apply, and post compensating reversals for applied notes.' },
      { key: 'finance:view_debtors',             label: 'View debtors',                     description: 'View aged-debtors reports.' },
      { key: 'finance:view_subledger',           label: 'View subledger',                   description: 'View detailed subledger journal entries.' },
      { key: 'finance:manage_billing_reconcile', label: 'Reconcile billing to claims',      description: 'Match billing runs against claim payments.' },
      { key: 'finance:view_payment_advice',      label: 'View payment advice',              description: 'View payment-advice notifications sent to providers.' },
      { key: 'finance:manage_copayments',        label: 'Manage cost-share receipts',       description: 'Record or adjust member cost-share (copayment) receipts.' },
      { key: 'finance:view_member_liabilities',  label: 'View member liabilities',          description: "View the fund-issued 'the member owes' ledger — one row per adjudicated claim with a cost-share balance." },
      { key: 'finance:view_withheld_tax',        label: 'View withheld tax',                description: 'View tax-withheld claims and payments.' },
      // ── Reinsurance sub-namespace (Phase 10) ──────────────────────────
      { key: 'finance.reinsurance:view',                     label: 'View reinsurance',                    description: 'Read access to reinsurers, treaties, cessions, and recoveries.' },
      { key: 'finance.reinsurance:manage_treaty',            label: 'Manage treaties',                     description: 'Full CRUD on reinsurers and treaties including draft creation and activation.' },
      { key: 'finance.reinsurance:cede_facultative',         label: 'Cede facultative',                    description: 'Create DRAFT facultative cessions against an active treaty.' },
      { key: 'finance.reinsurance:approve_facultative',      label: 'Approve facultative',                 description: 'Move facultative cessions DRAFT → APPROVED → CEDED and void pre-terminal facultatives.' },
      { key: 'finance.reinsurance:record_recovery_received', label: 'Record recovery received',            description: 'Record the recovery amount received from a reinsurer against an invoiced expectation.' },
      { key: 'finance.reinsurance:writeoff_recovery',        label: 'Write off recovery',                  description: 'Write off an uncollectable reinsurance recovery with a stated reason.' },
      { key: 'finance.reinsurance:resolve_review',           label: 'Resolve reinsurance review',          description: 'Resolve tasks in the reinsurance review queue (claim regression, recovery dispute, manual void requests).' },
    ],
  },
  {
    id: 'members',
    label: 'Members',
    permissions: [
      { key: 'members:view',                     label: 'View members',                     description: 'Read access to the member directory.' },
      { key: 'members:create',                   label: 'Add members',                      description: 'Register new members.' },
      { key: 'members:update',                   label: 'Edit members',                     description: 'Update member profile and enrollment details.' },
      { key: 'members:deactivate',               label: 'Deactivate members',               description: 'Suspend or terminate member coverage.' },
      { key: 'members:view_dependants',          label: 'View dependants',                  description: "Read access to a member's dependants." },
      { key: 'members:manage_waivers',           label: 'Manage special waivers',           description: 'Override benefit limits for individual members.' },
      { key: 'members:view_history',             label: 'View member history',              description: 'View claim, payment, and contribution history for a member.' },
    ],
  },
  {
    id: 'providers',
    label: 'Providers',
    permissions: [
      { key: 'providers:view',                   label: 'View providers',                   description: 'Read access to the provider directory.' },
      { key: 'providers:create',                 label: 'Onboard providers',                description: 'Register new healthcare providers.' },
      { key: 'providers:update',                 label: 'Edit providers',                   description: 'Update provider profile and credentials.' },
      { key: 'providers:manage_contracts',       label: 'Manage provider contracts',        description: 'Configure tariff agreements and payment terms with providers.' },
    ],
  },
  {
    id: 'admin',
    label: 'Tenant administration',
    permissions: [
      { key: 'admin:manage_roles',               label: 'Manage roles & permissions',       description: 'Create, edit, and assign tenant roles. Hold the keys to the kingdom.' },
      { key: 'admin:manage_users',               label: 'Manage staff users',               description: 'Invite, edit, and deactivate staff users.' },
      { key: 'admin:view_audit',                 label: 'View audit log',                   description: 'Read tenant audit events.' },
      { key: 'admin:manage_settings',            label: 'Manage tenant settings',           description: 'Edit branding, insurance lines, email templates, etc.' },
      { key: 'admin:manage_rules',               label: 'Manage rules engine',              description: 'Author and deploy tenant-specific business rules.' },
      { key: 'admin.bank_accounts:manage',       label: 'Manage bank accounts',             description: "Configure the tenant's own bank accounts used for outbound disbursements and inbound receipt matching." },
    ],
  },
  {
    id: 'platform',
    label: 'Platform administration',
    permissions: [
      { key: 'platform:view_jobs',               label: 'View scheduled jobs',              description: 'View scheduled job configs and recent run history. Platform admins only.' },
      { key: 'platform:manage_jobs',             label: 'Manage scheduled jobs',            description: 'Manually trigger jobs and edit schedules. Platform admins only.' },
    ],
  },
];

/** Flat set of every permission key — used by validation and tests. */
export const ALL_PERMISSIONS: ReadonlySet<PermissionKey> = new Set(
  PERMISSION_CATALOGUE.flatMap(d => d.permissions.map(p => p.key)),
);
