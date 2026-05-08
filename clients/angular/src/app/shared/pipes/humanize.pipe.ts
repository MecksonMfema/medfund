import { Pipe, PipeTransform } from '@angular/core';

/**
 * Humanises an enum-like value for display.
 *
 * <p>Backend stores canonical machine-readable codes
 * ({@code WRITTEN_OFF}, {@code BANK_TRANSFER}, {@code INDIVIDUAL_ONLY},
 * {@code mobile_money}, …); the UI should never expose the underscores or
 * forced casing. This pipe replaces underscores and hyphens with spaces and
 * sentence-cases the result, so {@code WRITTEN_OFF} renders as "Written off"
 * and {@code mobile_money} as "Mobile money".
 *
 * <p>CSS that applies {@code text-transform: uppercase} on a status badge
 * still works — the humanised string passes through fine.
 *
 * <p>Pass a non-string and you get the value back unchanged.
 */
@Pipe({ name: 'humanize', standalone: true })
export class HumanizePipe implements PipeTransform {

  transform(value: unknown): string {
    if (value === null || value === undefined) return '';
    if (typeof value !== 'string') return String(value);
    const trimmed = value.trim();
    if (!trimmed) return '';
    const spaced = trimmed.replace(/[_-]+/g, ' ').toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }
}
