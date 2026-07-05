import { InvoiceStatementComponent } from './invoice-statement.component';
import { StatementLine, StatementResponse } from '../../../../core/services/statements.service';

/**
 * Guards the double-entry ledger rendering. The component must split
 * OPENING_BALANCE and CLOSING_BALANCE marker rows out of the
 * transactions loop and route them to their own bookend getters so the
 * ledger renders as proper b/f + c/f. A regression that lets bookend
 * rows leak into transactionLines would double-render the balance.
 */

function line(overrides: Partial<StatementLine>): StatementLine {
  return {
    date: '2026-08-01T00:00:00Z',
    type: 'TRANSACTION',
    description: 'stub',
    runningBalance: '0.00',
    ...overrides,
  };
}

function statement(lines: StatementLine[]): StatementResponse {
  return {
    header: {
      targetType: 'GROUP',
      targetId: 'grp-1',
      targetName: 'Acme',
      targetCode: 'REG-1',
      periodStart: '2026-08-01',
      periodEnd: '2026-08-31',
      currencyCode: 'USD',
      openingBalance: '100.00',
      closingBalance: '250.00',
      totalCharges: '150.00',
      totalPayments: '0',
    },
    lines,
  };
}

// A bare component instance skips DI + ngOnInit; every accessor tested
// below reads only from `this.statement`, so no service stubs are needed.
function makeComp(lines: StatementLine[]): InvoiceStatementComponent {
  const c = Object.create(InvoiceStatementComponent.prototype) as InvoiceStatementComponent;
  (c as unknown as { statement: StatementResponse | null }).statement = statement(lines);
  return c;
}

describe('InvoiceStatementComponent bookend routing', () => {
  it('extracts OPENING_BALANCE into openingBalanceLine', () => {
    const opening = line({
      type: 'OPENING_BALANCE',
      description: 'Balance brought forward',
      runningBalance: '100.00',
    });
    const c = makeComp([opening, line({ description: 'mid' })]);

    expect(c.openingBalanceLine).toEqual(opening);
  });

  it('extracts CLOSING_BALANCE into closingBalanceLine', () => {
    const closing = line({
      type: 'CLOSING_BALANCE',
      description: 'Balance carried forward',
      runningBalance: '250.00',
    });
    const c = makeComp([line({ description: 'mid' }), closing]);

    expect(c.closingBalanceLine).toEqual(closing);
  });

  it('filters OPENING_BALANCE, CLOSING_BALANCE and CONTRIBUTION from transactionLines', () => {
    // If any of these leak into transactionLines they render twice —
    // once as the row and again as the bookend / scheme entry. The
    // filter is the load-bearing invariant of this component.
    const opening   = line({ type: 'OPENING_BALANCE',  description: 'b/f' });
    const contrib   = line({ type: 'CONTRIBUTION',     description: 'contribution' });
    const payment   = line({ type: 'TRANSACTION',      description: 'payment', credit: '50.00' });
    const adjust    = line({ type: 'TRANSACTION',      description: 'adjustment', debit: '10.00' });
    const legacy    = line({ type: 'CONTRIBUTION_PAID', description: 'legacy paid' });
    const closing   = line({ type: 'CLOSING_BALANCE',  description: 'c/f' });
    const c = makeComp([opening, contrib, payment, adjust, legacy, closing]);

    expect(c.transactionLines.map(l => l.description))
      .toEqual(['payment', 'adjustment', 'legacy paid']);
  });

  it('returns null bookend getters when the backend emits no marker rows', () => {
    // Legacy contributions-service payloads pre-cleanup have no
    // bookend rows. The getters must return null so the template
    // gracefully skips the b/f + c/f table rows.
    const c = makeComp([
      line({ type: 'TRANSACTION', description: 'only txn' }),
    ]);

    expect(c.openingBalanceLine).toBeNull();
    expect(c.closingBalanceLine).toBeNull();
    expect(c.transactionLines.length).toBe(1);
  });

  it('handles a fully empty ledger without crashing', () => {
    const c = makeComp([]);

    expect(c.openingBalanceLine).toBeNull();
    expect(c.closingBalanceLine).toBeNull();
    expect(c.transactionLines).toEqual([]);
  });
});
