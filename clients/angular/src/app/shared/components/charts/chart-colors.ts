import { Color, ScaleType } from '@swimlane/ngx-charts';

export const OCEAN_BREEZE_SCHEME: Color = {
  name: 'OceanBreeze',
  selectable: true,
  group: ScaleType.Ordinal,
  domain: ['#0077B6', '#00B4D8', '#90E0EF', '#2EC4B6', '#FF9F1C', '#03045E', '#CAF0F8', '#E71D36'],
};

/**
 * Palette mirroring the legacy MASCA dashboards (Masca-Claims-Admin,
 * Masca-Finance-Typescript, MASCA-Frontend). The first two entries are the
 * canonical USD-blue / ZWL-orange pairing every legacy chart used. Entries
 * beyond that are deterministic stand-ins for tenants that activate three or
 * more currencies in {@code tenant_currency_config}.
 *
 * Backend's {@code /tenant-stats/charts} emits per-currency series ordered
 * is_default-first, so the first colour always lands on the tenant's primary
 * currency.
 */
export const MASCA_LEGACY_PALETTE: Color = {
  name: 'MascaLegacy',
  selectable: true,
  group: ScaleType.Ordinal,
  domain: ['#42A5F5', '#FFA726', '#66BB6A', '#AB47BC', '#26C6DA', '#FF7043', '#5C6BC0', '#26A69A'],
};

/**
 * Twin-series palette for charts that plot two metrics in a single chart per
 * currency (Finance tab: Transactions vs Payments). Keeps the legacy
 * blue/orange pairing regardless of how many currencies the tenant has, since
 * each chart stays scoped to one currency anyway.
 */
export const MASCA_TWIN_PALETTE: Color = {
  name: 'MascaTwin',
  selectable: true,
  group: ScaleType.Ordinal,
  domain: ['#42A5F5', '#FFA726'],
};
