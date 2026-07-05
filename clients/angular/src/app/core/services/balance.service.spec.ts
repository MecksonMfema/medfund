import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { BalanceService, CreditorRow, PageResponse } from './balance.service';
import { environment } from '../../../environments/environment';

/**
 * Guards the wire shape of the new bad-debts endpoints. The controller
 * URL was intentionally split — /bad-debts now returns the deactivated /
 * terminated slice while the legacy aged-balances lifecycle view moved
 * to /aged-balances. If any of the URLs / param keys silently drift, the
 * page would fetch the wrong list and the operator would triage on
 * stale data, so pin them down explicitly.
 */
describe('BalanceService (bad-debts endpoints)', () => {
  let service: BalanceService;
  let http: HttpTestingController;

  const baseUrl = `${environment.apiBaseUrl}/billing/balances`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BalanceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('listBadDebts', () => {
    it('hits /bad-debts with all filter params encoded', () => {
      // The Angular page pipes subjectType + q into the URL exactly as
      // typed — the backend expects them under those keys and matches
      // them case-sensitively for the enum, so any silent rename here
      // would cause a permanent empty list on the Groups tab.
      const stubPage: PageResponse<CreditorRow> = {
        content: [], total: 0, page: 0, size: 20, totalPages: 1,
      };
      service.listBadDebts('USD', 'GROUP', 'Acme', 0, 20).subscribe(r => {
        expect(r.total).toBe(0);
      });

      const req = http.expectOne(r =>
        r.url === `${baseUrl}/bad-debts` && r.method === 'GET'
      );
      expect(req.request.params.get('currency')).toBe('USD');
      expect(req.request.params.get('subjectType')).toBe('GROUP');
      expect(req.request.params.get('q')).toBe('Acme');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(stubPage);
    });

    it('omits subjectType and q when unset', () => {
      // The "All" tab (BOTH-model default) sends no subjectType. If we
      // silently defaulted to "MEMBER" here the Groups half would
      // vanish from the aggregated list.
      service.listBadDebts('ZAR').subscribe();

      const req = http.expectOne(r => r.url === `${baseUrl}/bad-debts`);
      expect(req.request.params.has('subjectType')).toBe(false);
      expect(req.request.params.has('q')).toBe(false);
      expect(req.request.params.get('currency')).toBe('ZAR');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush({ content: [], total: 0, page: 0, size: 20, totalPages: 1 });
    });
  });

  describe('exportBadDebtsExcel', () => {
    it('requests the /bad-debts/export/excel path as a blob with matching filters', () => {
      // Blob response type is what lets us stitch the download link on
      // the client — a regression here would silently swap the download
      // for a JSON string.
      const blob = new Blob(['fake-xlsx'], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      service.exportBadDebtsExcel('USD', 'MEMBER', 'jane').subscribe(b => {
        expect(b).toBe(blob);
      });

      const req = http.expectOne(r =>
        r.url === `${baseUrl}/bad-debts/export/excel` && r.method === 'GET'
      );
      expect(req.request.responseType).toBe('blob');
      expect(req.request.params.get('currency')).toBe('USD');
      expect(req.request.params.get('subjectType')).toBe('MEMBER');
      expect(req.request.params.get('q')).toBe('jane');
      req.flush(blob);
    });
  });

  describe('listAged (aged-balances lifecycle view)', () => {
    it('now hits /aged-balances after the /bad-debts URL was re-scoped', () => {
      // Explicit guard: the aged-balances view moved off /bad-debts when
      // the bad-debts page was re-scoped to the deactivated + owing
      // filter. If someone flips this back to /bad-debts the two views
      // would collide server-side and both would return the wrong list.
      service.listAged('USD', 30, 'smith').subscribe();

      const req = http.expectOne(r =>
        r.url === `${baseUrl}/aged-balances` && r.method === 'GET'
      );
      expect(req.request.params.get('currency')).toBe('USD');
      expect(req.request.params.get('minAgeDays')).toBe('30');
      expect(req.request.params.get('q')).toBe('smith');
      req.flush({ content: [], total: 0, page: 0, size: 20, totalPages: 1 });
    });
  });
});
