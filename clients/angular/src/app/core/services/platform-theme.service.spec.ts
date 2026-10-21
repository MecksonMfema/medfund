import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PlatformBranding, PlatformThemeService } from './platform-theme.service';
import { environment } from '../../../environments/environment';

/**
 * The theme bootstrap runs inside an APP_INITIALIZER, so a thrown error here
 * would block the whole app from booting. These specs pin both halves of that
 * contract: the happy path applies the template + dark flag, and a failing
 * fetch resolves quietly with the stylesheet defaults left in place.
 */
describe('PlatformThemeService', () => {
  let service: PlatformThemeService;
  let http: HttpTestingController;

  const url = `${environment.apiBaseUrl}/public/platform/branding`;

  const branding: PlatformBranding = {
    platformName: 'MedFund Zim',
    logoUrl: '/api/v1/public/platform/logo?v=42',
    themeTemplateId: 'forest',
    darkMode: true,
    heroTitle: 'Welcome',
    heroSubtitle: 'Cover that travels with you',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PlatformThemeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    document.documentElement.removeAttribute('data-theme');
    document.body.removeAttribute('style');
  });

  it('applies the template CSS vars and the dark flag on bootstrap', () => {
    let done = false;
    service.bootstrap().subscribe(() => (done = true));

    http.expectOne(r => r.url === url && r.method === 'GET').flush(branding);

    expect(done).toBeTrue();
    // Forest's primary, straight off the shared template catalogue.
    expect(document.body.style.getPropertyValue('--color-primary')).toBe('#16a34a');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(service.current?.platformName).toBe('MedFund Zim');
  });

  it('sets the light theme when darkMode is false', () => {
    service.bootstrap().subscribe();
    http.expectOne(url).flush({ ...branding, darkMode: false });

    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('resolves without throwing when the branding fetch fails', () => {
    let done = false;
    service.bootstrap().subscribe(() => (done = true));

    http.expectOne(url).flush('boom', { status: 503, statusText: 'Service Unavailable' });

    expect(done).toBeTrue();
    expect(service.current).toBeNull();
  });

  it('resolves a server-relative logo path against the gateway origin', () => {
    const origin = environment.apiBaseUrl.replace('/api/v1', '');
    expect(service.logoSrc('/api/v1/public/platform/logo?v=42'))
      .toBe(`${origin}/api/v1/public/platform/logo?v=42`);
    expect(service.logoSrc('https://cdn.example.com/logo.png'))
      .toBe('https://cdn.example.com/logo.png');
    expect(service.logoSrc(null)).toBeNull();
  });
});
