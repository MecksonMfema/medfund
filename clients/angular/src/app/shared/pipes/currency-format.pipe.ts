import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats a (amount, currencyCode) pair for display. Uses {@link Intl.NumberFormat}
 * so the symbol, separators, and decimal places follow the user's locale and the
 * currency's ISO 4217 metadata.
 *
 * <p>Falls back to the raw amount when the currency code is missing or invalid —
 * so a partially-loaded row never throws.
 */
@Pipe({ name: 'currencyFormat', standalone: true })
export class CurrencyFormatPipe implements PipeTransform {

  transform(amount: number | string | null | undefined, currencyCode: string | null | undefined,
            locale: string = 'en-US'): string {
    if (amount === null || amount === undefined || amount === '') {
      return '';
    }
    const numeric = typeof amount === 'string' ? Number(amount) : amount;
    if (!Number.isFinite(numeric)) {
      return String(amount);
    }
    if (!currencyCode || currencyCode.length !== 3) {
      return numeric.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }
    try {
      return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency: currencyCode.toUpperCase(),
      }).format(numeric);
    } catch {
      return `${currencyCode} ${numeric.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    }
  }
}
