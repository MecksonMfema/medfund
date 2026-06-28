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
  | 'claims:assign' | 'claims:manage_tasks'
  | 'claims:view_ctc_payments' | 'claims:commit_ctc_payment'
  // Billing
  | 'billing:view' | 'billing:manage_schemes' | 'billing:manage_age_groups'
  | 'billing:manage_waiting_periods' | 'billing:manage_groups'
  | 'billing:manage_dependants' | 'billing:generate_billing'
  | 'billing:revoke_billing'
  | 'billing:view_statements' | 'billing:post_transactions'
  | 'billing:view_currencies' | 'billing:manage_currencies'
  | 'billing:manage_billing_settings'
  | 'billing:view_creditors' | 'billing:manage_bad_debts'
  // Finance
  | 'finance:view' | 'finance:create_payment_run' | 'finance:approve_payment_run'
  | 'finance:view_advance_payments' | 'finance:manage_advance_payments'
  | 'finance:manage_ctc_payments' | 'finance:manage_receipts'
  | 'finance:manage_banks' | 'finance:post_adjustments'
  | 'finance:view_debtors' | 'finance:view_subledger'
  | 'finance:manage_billing_reconcile' | 'finance:view_payment_advice'
  | 'finance:manage_copayments' | 'finance:view_withheld_tax'
  // Members
  | 'members:view' | 'members:create' | 'members:update' | 'members:deactivate'
  | 'members:view_dependants' | 'members:manage_waivers' | 'members:view_history'
  // Providers
  | 'providers:view' | 'providers:create' | 'providers:update'
  | 'providers:manage_contracts'
  // Tenant administration
  | 'admin:manage_roles' | 'admin:manage_users' | 'admin:view_audit'
  | 'admin:manage_settings' | 'admin:manage_rules'
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
      { key: 'claims:manage_tasks',              label: 'Manage claim tasks',               description: 'Create and re-assign claim work items.' },
      { key: 'claims:view_ctc_payments',         label: 'View CTC payments',                description: 'View cost-to-cure payment allocations.' },
      { key: 'claims:commit_ctc_payment',        label: 'Commit CTC payments',              description: 'Approve a cost-to-cure allocation for payment.' },
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
      { key: 'billing:view_creditors',           label: 'View creditors',                   description: 'View outstanding balances owed by members and groups.' },
      { key: 'billing:manage_bad_debts',         label: 'Manage bad debts',                 description: 'Write off receivables that cannot be collected.' },
    ],
  },
  {
    id: 'finance',
    label: 'Finance',
    permissions: [
      { key: 'finance:view',                     label: 'View finance',                     description: 'Read access to payment runs, payments, receipts, and reports.' },
      { key: 'finance:create_payment_run',       label: 'Create payment run',               description: 'Create a new draft batch payment run.' },
      { key: 'finance:approve_payment_run',      label: 'Approve payment run',              description: 'Execute a draft payment run — disburses funds.' },
      { key: 'finance:view_advance_payments',    label: 'View advance payments',            description: 'View provider prepayments.' },
      { key: 'finance:manage_advance_payments',  label: 'Manage advance payments',          description: 'Create, edit, or cancel provider prepayments.' },
      { key: 'finance:manage_ctc_payments',      label: 'Manage CTC payments',              description: 'Create or commit cost-to-cure payments from finance.' },
      { key: 'finance:manage_receipts',          label: 'Manage receipts',                  description: 'Capture and post receipts for member or group payments.' },
      { key: 'finance:manage_banks',             label: 'Manage bank accounts',             description: 'Configure tenant bank accounts and routing.' },
      { key: 'finance:post_adjustments',         label: 'Post adjustments',                 description: 'Apply manual adjustments to payments or receipts.' },
      { key: 'finance:view_debtors',             label: 'View debtors',                     description: 'View aged-debtors reports.' },
      { key: 'finance:view_subledger',           label: 'View subledger',                   description: 'View detailed subledger journal entries.' },
      { key: 'finance:manage_billing_reconcile', label: 'Reconcile billing to claims',      description: 'Match billing runs against claim payments.' },
      { key: 'finance:view_payment_advice',      label: 'View payment advice',              description: 'View payment-advice notifications sent to providers.' },
      { key: 'finance:manage_copayments',        label: 'Manage copayments',                description: 'Create or adjust member copayment records.' },
      { key: 'finance:view_withheld_tax',        label: 'View withheld tax',                description: 'View tax-withheld claims and payments.' },
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
