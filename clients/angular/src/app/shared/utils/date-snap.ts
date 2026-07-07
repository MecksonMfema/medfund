/**
 * Cycle-boundary date snapping.
 *
 * MedFund bills on monthly cycles. Two invariants keep arrears/rebate
 * math sign-correct:
 *   • *Starts* (enrolment, group change, scheme change, swap, override
 *     effectiveFrom) snap to the **1st of the month** — the new state
 *     applies to the whole cycle.
 *   • *Ends* (group terminate, dependant deactivate, scheme endDate,
 *     tariff endDate, pre-auth expiry, override effectiveTo) snap to
 *     the **last day of the month** — the old state stays billable
 *     through the cycle in which it ends, avoiding a mid-cycle clip.
 *
 * See MEMORY: `feedback_effective_date_snap.md`.
 */

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function iso(y: number, m: number, d: number): string {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function daysInMonth(year: number, month1to12: number): number {
  // Day 0 of month+1 is the last day of month.
  return new Date(year, month1to12, 0).getDate();
}

/** Today, ISO date, in local time — the anchor for the *Offset helpers. */
function today(): { y: number; m: number } {
  const t = new Date();
  return { y: t.getFullYear(), m: t.getMonth() + 1 };
}

/**
 * Snap an ISO date string to the 1st of its month. Returns the input
 * unchanged when the string isn't a well-formed `YYYY-MM-DD` — callers
 * (blur handlers, form validators) can rely on this being a no-op for
 * partially-typed values.
 */
export function firstOfMonth(isoDate: string | null | undefined): string {
  if (!isoDate || !ISO_DATE_RE.test(isoDate)) return isoDate ?? '';
  const [y, m] = isoDate.split('-');
  return `${y}-${m}-01`;
}

/**
 * Snap an ISO date string to the last day of its month. Handles
 * variable month lengths + leap years (Feb 29 vs 28). Passthrough on
 * malformed input, same contract as {@link firstOfMonth}.
 */
export function endOfMonth(isoDate: string | null | undefined): string {
  if (!isoDate || !ISO_DATE_RE.test(isoDate)) return isoDate ?? '';
  const [ys, ms] = isoDate.split('-');
  const y = Number(ys);
  const m = Number(ms);
  return iso(y, m, daysInMonth(y, m));
}

/** 1st of the month `offset` months from today (0 = this month). */
export function firstOfMonthOffset(offset: number): string {
  const { y, m } = today();
  const d = new Date(y, m - 1 + offset, 1);
  return iso(d.getFullYear(), d.getMonth() + 1, 1);
}

/** Last day of the month `offset` months from today (0 = this month). */
export function endOfMonthOffset(offset: number): string {
  const { y, m } = today();
  const d = new Date(y, m - 1 + offset + 1, 0);
  return iso(d.getFullYear(), d.getMonth() + 1, d.getDate());
}

/** True when the ISO date is strictly before the 1st of the current month. */
export function isBackdated(isoDate: string | null | undefined): boolean {
  if (!isoDate || !ISO_DATE_RE.test(isoDate)) return false;
  return isoDate < firstOfMonthOffset(0);
}
