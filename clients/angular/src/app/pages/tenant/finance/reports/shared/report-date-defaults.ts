/**
 * Shared default reporting window for every finance report page: the
 * previous full calendar month. Kept in one file so every page (there are
 * ~30+) uses the same defaults and the window can be re-tuned in a single
 * place if the product ever decides to widen it.
 */

/** First day of the previous full calendar month, ISO date. */
export function defaultReportPeriodStart(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return d.toISOString().slice(0, 10);
}

/** Last day of the previous full calendar month, ISO date. */
export function defaultReportPeriodEnd(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return d.toISOString().slice(0, 10);
}
