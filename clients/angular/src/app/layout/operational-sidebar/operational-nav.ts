import { PermissionKey } from '../../core/security/permissions';

/**
 * Sidebar item shape — declares the permission(s) under which the item is
 * visible. ANY-of semantics: holding at least one permission renders the
 * item. An empty/missing array means the item is always visible.
 *
 * <p>{@code exactMatch} = true forces routerLinkActive to use exact path
 * comparison instead of prefix. Set this on any item whose route is a
 * prefix of another sibling item's route — e.g. {@code /tenant/claims}
 * needs exactMatch because {@code /tenant/claims/drug} is a separate
 * item; without exact, both stay highlighted when on the drug subpath.
 *
 * <p>{@code featureFlag} ties an item's visibility to a tenant feature
 * toggle from {@code TenantService.tenant$}. The sidebar resolves the flag
 * at render time:
 * <ul>
 *   <li>{@code 'drugClaims'} — tenant has {@code drugClaimsEnabled !== false}.</li>
 *   <li>{@code 'ageGroupsAvailable'} — tenant has at least one person-centric
 *       insurance line (HEALTH/LIFE/FUNERAL/GROUP/TRAVEL/DISABILITY).
 *       Hides Age Groups for asset-only tenants (VEHICLE/PROPERTY).</li>
 * </ul>
 */
export interface OperationalNavItem {
  label: string;
  icon: string;
  route: string;
  permissions?: PermissionKey[];
  exactMatch?: boolean;
  featureFlag?: 'drugClaims' | 'ageGroupsAvailable';
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
      { label: 'Schemes',        icon: 'briefcase',      route: '/tenant/billing/schemes',      permissions: ['billing:view', 'billing:manage_schemes'], exactMatch: true },
      { label: 'Age Groups',     icon: 'users',          route: '/tenant/billing/age-groups',   permissions: ['billing:view', 'billing:manage_age_groups'], featureFlag: 'ageGroupsAvailable' },
      { label: 'Generate',       icon: 'play-circle',    route: '/tenant/billing/generate',     permissions: ['billing:generate_billing'] },
      { label: 'Contributions',  icon: 'list',           route: '/tenant/billing/view',         permissions: ['billing:view'] },
      { label: 'Statements',     icon: 'file-text',      route: '/tenant/billing/statements',   permissions: ['billing:view_statements'] },
      { label: 'Transactions',   icon: 'activity',       route: '/tenant/billing/transactions', permissions: ['billing:post_transactions', 'billing:view'] },
      { label: 'Creditors',      icon: 'trending-down',  route: '/tenant/billing/creditors',    permissions: ['billing:view_creditors'] },
      { label: 'Bad Debts',      icon: 'alert-triangle', route: '/tenant/billing/bad-debts',    permissions: ['billing:manage_bad_debts'] },
      { label: 'Group Charge',   icon: 'building',       route: '/tenant/billing/group-charge', permissions: ['billing:view_creditors'] },
    ],
  },
  {
    title: 'Members',
    items: [
      { label: 'Members',     icon: 'users',     route: '/tenant/members',          permissions: ['members:view'] },
      { label: 'Dependants',  icon: 'user-plus', route: '/tenant/members/dependants', permissions: ['members:view_dependants'] },
      { label: 'Groups',      icon: 'building',  route: '/tenant/members/groups',   permissions: ['billing:manage_groups'] },
    ],
  },
  {
    title: 'Claims',
    items: [
      // exactMatch on the parent route — Drug Claims, Pre-Auth, Tariffs and
      // Tasks are all children that would otherwise keep this item highlighted.
      { label: 'All Claims',         icon: 'file-medical',  route: '/tenant/claims',         permissions: ['claims:view'], exactMatch: true },
      { label: 'Drug Claims',        icon: 'wallet',        route: '/tenant/claims/drug',    permissions: ['claims:view_drug'], featureFlag: 'drugClaims' },
      { label: 'Pre-Authorizations', icon: 'check-circle',  route: '/tenant/claims/preauth', permissions: ['claims:manage_preauth'] },
      { label: 'Tariffs',            icon: 'banknote',      route: '/tenant/claims/tariffs', permissions: ['claims:manage_tariffs'] },
      { label: 'Tasks',              icon: 'clipboard',     route: '/tenant/claims/tasks',   permissions: ['claims:manage_tasks'] },
    ],
  },
  {
    title: 'Finance',
    items: [
      { label: 'Dashboard',          icon: 'chart',        route: '/tenant/finance',                       permissions: ['finance:view'] },
      { label: 'Payment Runs',       icon: 'play-circle',  route: '/tenant/finance/runs',                  permissions: ['finance:view', 'finance:create_payment_run'] },
      { label: 'Payments',           icon: 'credit-card',  route: '/tenant/finance/payments',              permissions: ['finance:view'] },
      { label: 'Advance Payments',   icon: 'credit-card',  route: '/tenant/finance/payments/advance',      permissions: ['finance:view_advance_payments'] },
      { label: 'CTC Payments',       icon: 'credit-card',  route: '/tenant/finance/payments/ctc',          permissions: ['finance:manage_ctc_payments'] },
      { label: 'Provider Creditors', icon: 'building',     route: '/tenant/finance/creditors/provider',    permissions: ['billing:view_creditors'] },
      { label: 'Reconciliation',     icon: 'wallet',       route: '/tenant/finance/reconciliations',       permissions: ['finance:view'] },
      { label: 'Adjustments',        icon: 'edit',         route: '/tenant/finance/adjustments',           permissions: ['finance:post_adjustments'] },
      { label: 'Debit Notes',        icon: 'edit',         route: '/tenant/finance/debit-notes',           permissions: ['finance:post_adjustments'] },
      { label: 'Credit Notes',       icon: 'edit',         route: '/tenant/finance/credit-notes',          permissions: ['finance:post_adjustments'] },
      { label: 'Payment Advice',     icon: 'wallet',       route: '/tenant/finance/advice',                permissions: ['finance:view_payment_advice'] },
      { label: 'Platform Banks',     icon: 'building',     route: '/tenant/finance/banks/masca',           permissions: ['finance:manage_banks'] },
      { label: 'Copayments',         icon: 'wallet',       route: '/tenant/finance/copayments',            permissions: ['finance:manage_copayments'] },
      { label: 'Reports',            icon: 'chart',        route: '/tenant/finance/reports',               permissions: ['finance:view_debtors', 'finance:view_subledger'] },
    ],
  },
];
