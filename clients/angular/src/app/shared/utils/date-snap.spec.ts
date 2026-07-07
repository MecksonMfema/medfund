import { endOfMonth, endOfMonthOffset, firstOfMonth, firstOfMonthOffset, isBackdated } from './date-snap';

describe('date-snap utilities', () => {
  describe('firstOfMonth', () => {
    it('snaps a mid-month ISO date to day 01', () => {
      expect(firstOfMonth('2026-07-15')).toBe('2026-07-01');
    });

    it('is a no-op for the 1st of the month', () => {
      expect(firstOfMonth('2026-07-01')).toBe('2026-07-01');
    });

    it('passes malformed input through unchanged', () => {
      expect(firstOfMonth('')).toBe('');
      expect(firstOfMonth(null)).toBe('');
      expect(firstOfMonth('2026-7-1')).toBe('2026-7-1');
      expect(firstOfMonth('not-a-date')).toBe('not-a-date');
    });
  });

  describe('endOfMonth', () => {
    it('snaps a mid-month ISO date to the last day of the month', () => {
      expect(endOfMonth('2026-07-15')).toBe('2026-07-31');
    });

    it('handles 30-day months', () => {
      expect(endOfMonth('2026-04-01')).toBe('2026-04-30');
      expect(endOfMonth('2026-11-15')).toBe('2026-11-30');
    });

    it('handles February in a non-leap year', () => {
      expect(endOfMonth('2027-02-15')).toBe('2027-02-28');
    });

    it('handles February in a leap year', () => {
      expect(endOfMonth('2028-02-15')).toBe('2028-02-29');
    });

    it('is a no-op for the last day of the month', () => {
      expect(endOfMonth('2026-01-31')).toBe('2026-01-31');
    });

    it('passes malformed input through unchanged', () => {
      expect(endOfMonth(undefined)).toBe('');
      expect(endOfMonth('not-a-date')).toBe('not-a-date');
      expect(endOfMonth('2026-7-1')).toBe('2026-7-1');
    });
  });

  describe('firstOfMonthOffset', () => {
    it('returns a well-formed YYYY-MM-01 for offset 0', () => {
      expect(firstOfMonthOffset(0)).toMatch(/^\d{4}-\d{2}-01$/);
    });

    it('advances by whole months, wrapping the year', () => {
      const now = new Date();
      const twelveMonthsForward = firstOfMonthOffset(12);
      expect(twelveMonthsForward.slice(0, 4)).toBe(String(now.getFullYear() + 1));
      expect(twelveMonthsForward.slice(5, 7)).toBe(String(now.getMonth() + 1).padStart(2, '0'));
      expect(twelveMonthsForward.slice(8)).toBe('01');
    });
  });

  describe('endOfMonthOffset', () => {
    it('returns a well-formed YYYY-MM-<lastDay>', () => {
      expect(endOfMonthOffset(0)).toMatch(/^\d{4}-\d{2}-(28|29|30|31)$/);
    });

    it('is the last day of the month `offset` months out', () => {
      // Compare against a fresh Date computed the same way.
      const t = new Date();
      const target = new Date(t.getFullYear(), t.getMonth() + 3 + 1, 0);
      const expected = `${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}-${String(target.getDate()).padStart(2, '0')}`;
      expect(endOfMonthOffset(3)).toBe(expected);
    });
  });

  describe('isBackdated', () => {
    it('is true when the date is before the first of this month', () => {
      expect(isBackdated(firstOfMonthOffset(-1))).toBe(true);
    });

    it('is false for this month or forward', () => {
      expect(isBackdated(firstOfMonthOffset(0))).toBe(false);
      expect(isBackdated(firstOfMonthOffset(1))).toBe(false);
    });

    it('is false for malformed input', () => {
      expect(isBackdated('')).toBe(false);
      expect(isBackdated('bogus')).toBe(false);
    });
  });
});
