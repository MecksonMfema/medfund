import { PermissionKey } from '../../core/security/permissions';

/**
 * Sidebar item shape — declares the permission(s) under which the item is
 * visible. ANY-of semantics: holding at least one permission renders the
 * item. An empty/missing array means the item is always visible.
 *
 * <p>{@code exactMatch} = true forces routerLinkActive to use exact path
 * comparison instead of prefix. Set this on any item whose route is a
 * prefix of another sibling item's route — e.g. {@code /tenant/claims}
 * needs exactMatch because {@code /tenant/claims/preauth} is a separate
 * item; without exact, both stay highlighted when on the sub-path.
 *
 * <p>{@code featureFlag} ties an item's visibility to a tenant feature
 * toggle from {@code TenantService.tenant$}. The sidebar resolves the flag
 * at render time:
 * <ul>
 *   <li>{@code 'drugClaims'} — tenant has {@code drugClaimsEnabled !== false}.</li>
 *   <li>{@code 'ageGroupsAvailable'} — tenant has at least one person-centric
 *       insurance line (HEALTH/LIFE/FUNERAL/GROUP/TRAVEL/DISABILITY).
 *       Hides Age Groups for asset-only tenants (VEHICLE/PROPERTY).</li>
 *   <li>{@code 'groupsAvailable'} — tenant's membership model is not
 *       {@code INDIVIDUAL_ONLY}. Hides Groups for individual-only tenants
 *       that have no employer-group entities.</li>
 * </ul>
 */
export interface OperationalNavItem {
  label: string;
  icon: string;
  route: string;
  permissions?: PermissionKey[];
  exactMatch?: boolean;
  featureFlag?:
      | 'drugClaims'
      | 'ageGroupsAvailable'
      | 'groupsAvailable'
      | 'vehiclesAvailable'
      | 'propertiesAvailable'
      | 'lifeAvailable'
      | 'funeralAvailable'
      | 'travelAvailable'
      | 'disabilityAvailable'
      | 'preauthAvailable';
}

/**
 * Group of related items with a common section heading. Empty groups
 * (every item filtered out by permissions) collapse — the user never
 * sees the heading.
 */
export interface OperationalNavGroup {
  title: string;
  items: OperationalNavItem[];
}

/**
 * Top-level operational navigation. Mirrors the four legacy sections —
 * Claims, Billing, Finance, Members — plus a global Dashboard. Detailed
 * sub-pages live under each section's route prefix and are scaffolded
 * separately in Phase 4 (most rendered as ComingSoon for now).
 *
 * <p>Order matters: rendered top-to-bottom in the sidebar.
 */
export const OPERATIONAL_NAV: OperationalNavGroup[] = [
  {
    title: 'Overview',
    items: [
      { label: 'Dashboard', icon: 'dashboard', route: '/tenant/dashboard' },
    ],
  },
  {
    title: 'Billing',
    items: [
      { label: 'Transactions',       icon: 'activity',    route: '/tenant/billing/transactions',     permissions: ['billing:post_transactions', 'billing:view'], exactMatch: true },
      { label: 'Record Transaction', icon: 'credit-card', route: '/tenant/billing/transactions/add', permissions: ['billing:post_transactions'] },
      { label: 'Schemes',        icon: 'briefcase',      route: '/tenant/billing/schemes',      permissions: ['billing:view', 'billing:manage_schemes'], exactMatch: true },
      { label: 'Age Groups',     icon: 'users',          route: '/tenant/billing/age-groups',   permissions: ['billing:view', 'billing:manage_age_groups'], featureFlag: 'ageGroupsAvailable' },
      { label: 'Generate',       icon: 'play-circle',    route: '/tenant/billing/generate',     permissions: ['billing:generate_billing'] },
      { label: 'Contribution Statements', icon: 'list',           route: '/tenant/billing/view',         permissions: ['billing:view'] },
      { label: 'Ledger',         icon: 'file-text',      route: '/tenant/billing/ledger',       permissions: ['billing:view_statements'] },
      { label: 'Debtors',        icon: 'trending-down',  route: '/tenant/billing/debtors',      permissions: ['billing:view_debtors'] },
      { label: 'Bad Debts',      icon: 'alert-triangle', route: '/tenant/billing/bad-debts',    permissions: ['billing:manage_bad_debts'] },
      { label: 'Charge Preview', icon: 'building',       route: '/tenant/billing/charge-preview', permissions: ['billing:view_debtors'] },
    ],
  },
  {
    title: 'Policies',
    items: [
      // Six per-line policy CRUD surfaces (V032 + Part 4). Each entry
      // is gated by the tenant having the corresponding insurance line
      // enabled in their settings.insuranceLines — a tenant who only
      // sells HEALTH never sees the Motor / Property / Life rows.
      { label: 'Vehicles',    icon: 'shield',  route: '/tenant/policies/vehicles',    featureFlag: 'vehiclesAvailable' },
      { label: 'Properties',  icon: 'shield',  route: '/tenant/policies/properties',  featureFlag: 'propertiesAvailable' },
      { label: 'Life',        icon: 'shield',  route: '/tenant/policies/life',        featureFlag: 'lifeAvailable' },
      { label: 'Funeral',     icon: 'shield',  route: '/tenant/policies/funeral',     featureFlag: 'funeralAvailable' },
      { label: 'Travel',      icon: 'shield',  route: '/tenant/policies/travel',      featureFlag: 'travelAvailable' },
      { label: 'Disability',  icon: 'shield',  route: '/tenant/policies/disability',  featureFlag: 'disabilityAvailable' },
    ],
  },
  {
    title: 'Members',
    items: [
      // exactMatch on the parent so the per-member detail route
      // (/tenant/members/:id) doesn't keep this row highlighted alongside
      // the detail page. Dependants are edited inside Member Detail —
      // no separate sidebar entry.
      { label: 'Members',     icon: 'users',     route: '/tenant/members',          permissions: ['members:view'], exactMatch: true },
      // Real Groups CRUD lives under the billing tree (GroupsListComponent +
      // GroupDetailComponent). Surface it under Members for navigation, and
      // gate it on membershipModel — INDIVIDUAL_ONLY tenants have no group
      // entities.
      { label: 'Groups',      icon: 'building',  route: '/tenant/billing/groups',   permissions: ['billing:manage_groups'], featureFlag: 'groupsAvailable' },
    ],
  },
  {
    title: 'Claims',
    items: [
      // exactMatch on the parent route — Drug Claims, Pre-Auth, and
      // Tariffs are all children that would otherwise keep this item highlighted.
      { label: 'All Claims',         icon: 'file-medical',  route: '/tenant/claims',         permissions: ['claims:view', 'claims:view_drug'], exactMatch: true },
      { label: 'Submit Claim',       icon: 'file-text',     route: '/tenant/claims/submit',  permissions: ['claims:create'] },
      { label: 'Eligibility Quote',  icon: 'calculator',    route: '/tenant/claims/eligibility-quote', permissions: ['claims:request_quote'] },
      { label: 'Pre-Authorizations', icon: 'check-circle',  route: '/tenant/claims/preauth', permissions: ['claims:manage_preauth'], featureFlag: 'preauthAvailable', exactMatch: true },
      { label: 'New Pre-Auth',       icon: 'plus',          route: '/tenant/claims/preauth/new', permissions: ['claims:manage_preauth'], featureFlag: 'preauthAvailable' },
      { label: 'Tariffs',            icon: 'banknote',      route: '/tenant/claims/tariffs', permissions: ['claims:manage_tariffs'] },
    ],
  },
  {
    title: 'Finance',
    items: [
      // Payments (top-level list) is intentionally omitted from the sidebar
      // — payments are now reached by drilling into a run detail page
      // (/tenant/finance/runs/:id), which shows the payments-in-run table
      // inline with the run summary. The /tenant/finance/payments route
      // still resolves for direct URLs / legacy bookmarks.
      { label: 'Payment Runs',       icon: 'play-circle',  route: '/tenant/finance/runs',                  permissions: ['finance:view', 'finance:create_payment_run'] },
      { label: 'Advance Payments',   icon: 'credit-card',  route: '/tenant/finance/payments/advance',      permissions: ['finance:view_advance_payments'] },
      { label: 'CTC Payments',       icon: 'credit-card',  route: '/tenant/finance/payments/ctc',          permissions: ['finance:manage_ctc_payments'] },
      { label: 'Creditors',          icon: 'building',     route: '/tenant/finance/creditors',             permissions: ['finance:view_creditors'] },
      { label: 'Reconciliation',     icon: 'wallet',       route: '/tenant/finance/reconciliations',       permissions: ['finance:view'] },
      { label: 'Notes',              icon: 'edit',         route: '/tenant/finance/notes',                 permissions: ['finance.notes:read'] },
      { label: 'Payment Advice',     icon: 'wallet',       route: '/tenant/finance/advice',                permissions: ['finance:view_payment_advice'] },
      { label: 'Cost-share receipts', icon: 'wallet',      route: '/tenant/finance/copayments',            permissions: ['finance:manage_copayments'] },
      { label: 'Member Liabilities', icon: 'wallet',       route: '/tenant/finance/member-liabilities',    permissions: ['finance:view_member_liabilities'] },
      { label: 'Reports',            icon: 'chart',        route: '/tenant/finance/reports',               permissions: ['finance:view_debtors', 'finance:view_subledger'] },
    ],
  },
];
