/**
 * Uniform envelope for every family-level report response. Mirrors the
 * Java-side `com.medfund.shared.report.ReportResponse<T>` — one place to
 * import this from is `report-envelope.ts`, callers unwrap `.data` at the
 * consumption site while keeping the envelope in scope so `perCurrency`,
 * `fxRates`, and `warnings` remain available for header strips and
 * warning banners.
 */

/**
 * Native-currency aggregate carried on every {@link ReportResponse.perCurrency}
 * entry. Fixed shape independent of the report's data type — never a paged
 * sub-slice.
 */
export interface PerCurrencyTotal {
  /** Sum in the currency this map is keyed by — never converted. */
  totalAmount: number;
  /** How many rows contributed to this per-currency total. */
  rowCount: number;
}

/** Aggregation grain — hint for chart bucketing, matches Java enum. */
export type PeriodGrain =
  | 'DAILY'
  | 'WEEKLY'
  | 'MONTHLY'
  | 'QUARTERLY'
  | 'YEARLY'
  | 'CUSTOM';

/** Reporting window; nullable on periodless controllers per G20. */
export interface ReportPeriod {
  periodStart: string;
  periodEnd: string;
  grain: PeriodGrain;
}

/**
 * Uniform envelope. `T` is the report-specific payload — a page slice, a
 * summary DTO, whatever the endpoint returns. The other four fields are
 * fixed-shape catalogue metadata.
 */
export interface ReportResponse<T> {
  reportKey: string;
  /** Nullable — periodless controllers omit this per G20. */
  period: ReportPeriod | null;
  /**
   * ISO-4217 code the server would convert scalar totals into. Row-level
   * amounts on paged reports stay native (G25) — this is the currency used
   * for any converted grand-total or for optional client-side conversion via
   * {@link ReportResponse.fxRates}.
   */
  reportingCurrency: string;
  data: T;
  /** Native ledger totals keyed by ISO-4217 code (G17). */
  perCurrency: Record<string, PerCurrencyTotal>;
  /**
   * Best-effort native→reporting rates keyed by native ISO-4217 code (G28).
   * A missing entry means the FX lookup failed for that currency — see
   * {@link ReportResponse.warnings}.
   */
  fxRates: Record<string, number>;
  /**
   * Human-readable notes such as
   * "FX not available for ZAR→USD as of 2026-08-11". Present when
   * {@link ReportResponse.fxRates} is incomplete.
   */
  warnings: string[];
  /** Server clock at response build time — ISO-8601. */
  generatedAt: string;
}
