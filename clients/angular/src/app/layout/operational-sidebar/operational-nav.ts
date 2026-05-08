import { PermissionKey } from '../../core/security/permissions';

/**
 * Sidebar item shape — declares the permission(s) under which the item is
 * visible. ANY-of semantics: holding at least one permission renders the
 * item. An empty/missing array means the item is always visible.
 */
export interface OperationalNavItem {
  label: string;
  icon: string;
  route: string;
  permissions?: PermissionKey[];
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
    title: 'Claims',
    items: [
      { label: 'All Claims',      icon: 'file-medical', route: '/tenant/claims',         permissions: ['claims:view'] },
      { label: 'Drug Claims',     icon: 'wallet',       route: '/tenant/claims/drug',    permissions: ['claims:view_drug'] },
      { label: 'Pre-Authorizations', icon: 'check-circle', route: '/tenant/claims/preauth', permissions: ['claims:manage_preauth'] },
      { label: 'Tariffs',         icon: 'banknote',     route: '/tenant/claims/tariffs', permissions: ['claims:manage_tariffs'] },
      { label: 'Tasks',           icon: 'clipboard',    route: '/tenant/claims/tasks',   permissions: ['claims:manage_tasks'] },
    ],
  },
  {
    title: 'Billing',
    items: [
      { label: 'Schemes',        icon: 'briefcase',  route: '/tenant/billing/schemes',      permissions: ['billing:view', 'billing:manage_schemes'] },
      { label: 'Generate',       icon: 'play-circle', route: '/tenant/billing/generate',    permissions: ['billing:generate_billing'] },
      { label: 'Statements',     icon: 'file-text',  route: '/tenant/billing/statements',   permissions: ['billing:view_statements'] },
      { label: 'Transactions',   icon: 'activity',   route: '/tenant/billing/transactions', permissions: ['billing:post_transactions', 'billing:view'] },
      { label: 'Currencies',     icon: 'dollar',     route: '/tenant/billing/currencies',   permissions: ['billing:view_currencies'] },
      { label: 'Creditors',      icon: 'trending-down', route: '/tenant/billing/creditors', permissions: ['billing:view_creditors'] },
    ],
  },
  {
    title: 'Finance',
    items: [
      { label: 'Payment Runs',   icon: 'play-circle',  route: '/tenant/finance/runs',         permissions: ['finance:view', 'finance:create_payment_run'] },
      { label: 'Payments',       icon: 'credit-card',  route: '/tenant/finance/payments',     permissions: ['finance:view'] },
      { label: 'Receipts',       icon: 'wallet',       route: '/tenant/finance/receipts',     permissions: ['finance:manage_receipts'] },
      { label: 'Banks',          icon: 'building',     route: '/tenant/finance/banks',        permissions: ['finance:manage_banks'] },
      { label: 'Adjustments',    icon: 'edit',         route: '/tenant/finance/adjustments',  permissions: ['finance:post_adjustments'] },
      { label: 'Reports',        icon: 'chart',        route: '/tenant/finance/reports',      permissions: ['finance:view_debtors', 'finance:view_subledger'] },
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
];
