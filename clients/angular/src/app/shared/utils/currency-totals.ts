/**
 * Per-currency aggregation helpers. Use anywhere a list of mixed-currency
 * rows needs to be totalled — the platform's Critical Rule 1 forbids
 * adding monetary values across currencies, so the result is grouped by
 * currencyCode rather than reduced to a single number.
 */

export interface CurrencyTotal {
  currencyCode: string;
  total: number;
  count: number;
}

/**
 * Group `rows` by currency and sum the numeric value at `amountKey` for
 * each group. Rows with a missing or non-numeric amount are counted but
 * contribute zero. Sorted descending by total — useful for "biggest
 * exposure first" displays.
 *
 * The amount and currency fields are looked up by key so callers can pass
 * any row shape without first mapping it.
 */
export function aggregateByCurrency<T>(
  rows: T[],
  amountKey: keyof T,
  currencyKey: keyof T,
): CurrencyTotal[] {
  const byCcy = new Map<string, CurrencyTotal>();
  for (const row of rows) {
    const code = String((row as Record<string, unknown>)[currencyKey as string] ?? '').trim();
    if (!code) continue;
    const v = byCcy.get(code) ?? { currencyCode: code, total: 0, count: 0 };
    v.count++;
    const raw = (row as Record<string, unknown>)[amountKey as string];
    const num = typeof raw === 'number' ? raw : Number(raw ?? 0);
    if (!Number.isNaN(num)) v.total += num;
    byCcy.set(code, v);
  }
  return Array.from(byCcy.values()).sort((a, b) => b.total - a.total);
}
