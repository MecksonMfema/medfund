import { TransactionFormComponent } from './transaction-form.component';
import { Member } from '../../../../core/services/members.service';

/**
 * Guards the client-side "grouped members can't be payers" rule. The
 * server also rejects with 422 in TransactionService.record; this spec
 * pins the picker filter so the UI never even offers a grouped member
 * as an option. See feedback_grouped_members_cannot_pay for the full
 * business context.
 */
function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1', memberNumber: 'M-001',
    firstName: 'Jane', lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 'j@x', phone: '',
    status: 'active', groupId: null, schemeId: null,
    enrollmentDate: '2024-01-01', createdAt: '2024-01-01',
    ...overrides,
  };
}

describe('TransactionFormComponent.toPayableMemberOptions', () => {
  it('drops members with a groupId', () => {
    const rows: Member[] = [
      makeMember({ id: 'm-1', groupId: null }),
      makeMember({ id: 'm-2', groupId: 'grp-1' }),
      makeMember({ id: 'm-3', groupId: null }),
    ];

    const opts = TransactionFormComponent.toPayableMemberOptions(rows);

    expect(opts.map(o => o.id)).toEqual(['m-1', 'm-3']);
  });

  it('shapes the option with combined first + last name and member number sublabel', () => {
    const rows: Member[] = [
      makeMember({ id: 'm-1', firstName: 'Jane', lastName: 'Doe', memberNumber: 'M-100' }),
    ];

    const opts = TransactionFormComponent.toPayableMemberOptions(rows);

    expect(opts).toEqual([{ id: 'm-1', label: 'Jane Doe', sublabel: 'M-100' }]);
  });

  it('returns empty when every result is grouped', () => {
    // Regression guard for the "picker looks empty but there are members"
    // UX case — the helper text under the input explains why.
    const rows: Member[] = [
      makeMember({ id: 'm-1', groupId: 'grp-1' }),
      makeMember({ id: 'm-2', groupId: 'grp-2' }),
    ];

    expect(TransactionFormComponent.toPayableMemberOptions(rows)).toEqual([]);
  });

  it('is a no-op on an empty input', () => {
    expect(TransactionFormComponent.toPayableMemberOptions([])).toEqual([]);
  });
});
