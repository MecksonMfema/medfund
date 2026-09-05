import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { UnsubscribePageComponent } from './unsubscribe-page.component';
import { ApiService } from '../../../core/services/api.service';

describe('UnsubscribePageComponent', () => {
  const validToken = '12345678-1234-1234-1234-123456789abc';

  let apiSpy: jasmine.SpyObj<ApiService>;

  const buildComponent = (token: string | null): ComponentFixture<UnsubscribePageComponent> => {
    apiSpy = jasmine.createSpyObj('ApiService', ['post']);
    const paramMap = convertToParamMap(token ? { token } : {});
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [UnsubscribePageComponent],
      providers: [
        { provide: ApiService, useValue: apiSpy },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap } } },
      ],
    });
    const fixture = TestBed.createComponent(UnsubscribePageComponent);
    fixture.detectChanges();
    return fixture;
  };

  it('flags malformed tokens on init without any HTTP call', () => {
    const fixture = buildComponent('not-a-uuid');
    expect(fixture.componentInstance.state).toBe('invalid_token');
    fixture.componentInstance.confirm();
    expect(apiSpy.post).not.toHaveBeenCalled();
  });

  it('POSTs the token and shows success when the backend deactivates', () => {
    const fixture = buildComponent(validToken);
    apiSpy.post.and.returnValue(of({ success: true, email: 'a@b.com' }));
    fixture.componentInstance.reason = 'Too many emails';
    fixture.componentInstance.confirm();
    expect(apiSpy.post).toHaveBeenCalledWith(
      `/report-schedule-recipients/unsubscribe/${validToken}`,
      { reason: 'Too many emails' });
    expect(fixture.componentInstance.state).toBe('success');
    expect(fixture.componentInstance.deactivatedEmail).toBe('a@b.com');
  });

  it('omits the reason field when the textarea is empty/whitespace', () => {
    const fixture = buildComponent(validToken);
    apiSpy.post.and.returnValue(of({ success: true, email: 'a@b.com' }));
    fixture.componentInstance.reason = '   ';
    fixture.componentInstance.confirm();
    expect(apiSpy.post).toHaveBeenCalledWith(
      `/report-schedule-recipients/unsubscribe/${validToken}`,
      {});
  });

  it('shows the not_found state when success=false (unknown/expired token)', () => {
    const fixture = buildComponent(validToken);
    apiSpy.post.and.returnValue(of({ success: false, email: null }));
    fixture.componentInstance.confirm();
    expect(fixture.componentInstance.state).toBe('not_found');
    expect(fixture.componentInstance.deactivatedEmail).toBeNull();
  });

  it('surfaces backend errors and allows retry', () => {
    const fixture = buildComponent(validToken);
    apiSpy.post.and.returnValue(throwError(() => ({ error: { detail: 'Server on fire' } })));
    fixture.componentInstance.confirm();
    expect(fixture.componentInstance.state).toBe('error');
    expect(fixture.componentInstance.errorDetail).toBe('Server on fire');

    apiSpy.post.and.returnValue(of({ success: true, email: 'a@b.com' }));
    fixture.componentInstance.confirm();
    expect(fixture.componentInstance.state).toBe('success');
  });

  it('is a noop when confirm is called without a token', () => {
    const fixture = buildComponent(null);
    fixture.componentInstance.confirm();
    expect(apiSpy.post).not.toHaveBeenCalled();
  });
});
