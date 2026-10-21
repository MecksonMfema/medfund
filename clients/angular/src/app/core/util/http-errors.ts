import { HttpErrorResponse } from '@angular/common/http';

/**
 * Extracts a human-readable error message from a backend response.
 * Prefers RFC 7807 problem-details fields (detail, then title); falls back
 * to a caller-supplied string when neither is present or the response is
 * not shaped like a JSON body.
 */
export function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object') {
    const body = (err as HttpErrorResponse).error;
    if (body && typeof body === 'object') {
      const detail = (body as { detail?: unknown }).detail;
      const title  = (body as { title?: unknown  }).title;
      if (typeof detail === 'string' && detail.trim()) return detail;
      if (typeof title  === 'string' && title.trim())  return title;
    }
  }
  return fallback;
}

/**
 * Composes an aggregated warning toast message from a report envelope's
 * warnings[] array: one header line, then one line per warning. The toast
 * container uses `white-space: pre-line`, so `\n` separators render as
 * line breaks. Returns an empty string on empty input so callers can
 * short-circuit without conditionals.
 */
export function composeWarningsToast(warnings: readonly string[], subject: string): string {
  const count = warnings.length;
  if (count === 0) return '';
  const header = `${subject}: partial data (${count} ${count === 1 ? 'series' : 'series'} unavailable)`;
  return [header, ...warnings].join('\n');
}
