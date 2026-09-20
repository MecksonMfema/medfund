import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { SettingsComponent } from './settings.component';
import {
  AdminService,
  PlatformFeatureFlag,
  PlatformSettings,
} from '../../../core/services/admin.service';
import { PlatformThemeService } from '../../../core/services/platform-theme.service';
import { FeatureFlagService } from '../../../core/services/feature-flag.service';
import { ToastService } from '../../../shared/components/toast/toast.service';

/**
 * This page shipped for months with Save buttons that had no click handler at
 * all, so the specs here are deliberately about the wiring: that each Save
 * sends the right patch, that a flag toggle persists the flipped value rather
 * than mutating local state, and that failures surface instead of going
 * silent.
 */
describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let component: SettingsComponent;
  let admin: jasmine.SpyObj<AdminService>;
  let theme: jasmine.SpyObj<PlatformThemeService>;
  let flags: jasmine.SpyObj<FeatureFlagService>;
  let toast: jasmine.SpyObj<ToastService>;

  const settings: PlatformSettings = {
    platformName: 'MedFund',
    supportEmail: 'support@medfund.co.zw',
    logoUrl: '/api/v1/public/platform/logo?v=7',
    themeTemplateId: 'ocean',
    darkMode: false,
    heroTitle: 'Welcome',
    heroSubtitle: 'Cover that travels with you',
    updatedAt: '2026-09-13T08:00:00Z',
    updatedBy: 'admin@medfund.co.zw',
  };

  const flagRow: PlatformFeatureFlag = {
    key: 'AI_ADJUDICATION',
    name: 'AI-assisted claims adjudication',
    description: 'Score and pre-adjudicate claims.',
    enabled: false,
    updatedAt: null,
    updatedBy: null,
  };

  beforeEach(async () => {
    admin = jasmine.createSpyObj<AdminService>('AdminService', [
      'getPlatformSettings', 'updatePlatformSettings', 'uploadPlatformLogo',
      'getFeatureFlags', 'updateFeatureFlag',
    ]);
    theme = jasmine.createSpyObj<PlatformThemeService>(
      'PlatformThemeService', ['applyFromSettings', 'logoSrc']);
    flags = jasmine.createSpyObj<FeatureFlagService>('FeatureFlagService', ['ingest']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);

    admin.getPlatformSettings.and.returnValue(of(settings));
    admin.getFeatureFlags.and.returnValue(of([{ ...flagRow }]));
    theme.logoSrc.and.callFake(url => (url ? `http://localhost:3000${url}` : null));

    await TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        { provide: AdminService, useValue: admin },
        { provide: PlatformThemeService, useValue: theme },
        { provide: FeatureFlagService, useValue: flags },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('drops the Email Templates tab', () => {
    expect(component.tabs.map(t => t.id)).toEqual(['general', 'appearance', 'features']);
  });

  it('hydrates the form from the persisted settings row', () => {
    expect(component.platformName).toBe('MedFund');
    expect(component.selectedThemeId).toBe('ocean');
    expect(component.logoSrc).toBe('http://localhost:3000/api/v1/public/platform/logo?v=7');
    expect(component.loading).toBeFalse();
  });

  it('saves the general tab as a name + email patch', () => {
    admin.updatePlatformSettings.and.returnValue(of(settings));
    component.platformName = 'MedFund Zim';

    component.saveGeneral();

    expect(admin.updatePlatformSettings).toHaveBeenCalledWith({
      platformName: 'MedFund Zim',
      supportEmail: 'support@medfund.co.zw',
    });
    expect(toast.success).toHaveBeenCalledWith('Platform identity saved.');
    expect(component.saving).toBeFalse();
  });

  it('saves the appearance tab as a theme + dark-mode patch and re-skins', () => {
    const saved = { ...settings, themeTemplateId: 'forest', darkMode: true };
    admin.updatePlatformSettings.and.returnValue(of(saved));
    component.selectTheme('forest');
    component.darkMode = true;

    component.saveAppearance();

    expect(admin.updatePlatformSettings).toHaveBeenCalledWith({
      themeTemplateId: 'forest',
      darkMode: true,
    });
    expect(theme.applyFromSettings).toHaveBeenCalledWith(saved);
  });

  it('saves the landing copy separately from the theme', () => {
    admin.updatePlatformSettings.and.returnValue(of(settings));
    component.heroTitle = 'Hello';
    component.heroSubtitle = 'There';

    component.saveLandingCopy();

    expect(admin.updatePlatformSettings).toHaveBeenCalledWith({
      heroTitle: 'Hello',
      heroSubtitle: 'There',
    });
  });

  it('surfaces a save failure as an error toast and clears the saving flag', () => {
    admin.updatePlatformSettings.and.returnValue(
      throwError(() => ({ error: { detail: 'Support email is not valid' } })));

    component.saveGeneral();

    expect(toast.error).toHaveBeenCalledWith('Support email is not valid');
    expect(component.saving).toBeFalse();
  });

  it('persists a flag toggle and takes the enabled value from the server', () => {
    admin.updateFeatureFlag.and.returnValue(of({ ...flagRow, enabled: true }));

    component.toggleFlag(component.featureFlags[0]);

    expect(admin.updateFeatureFlag).toHaveBeenCalledWith('AI_ADJUDICATION', true);
    expect(component.featureFlags[0].enabled).toBeTrue();
    expect(flags.ingest).toHaveBeenCalledWith(component.featureFlags);
  });

  it('leaves the toggle untouched when the flag update fails', () => {
    admin.updateFeatureFlag.and.returnValue(throwError(() => ({ status: 500 })));

    component.toggleFlag(component.featureFlags[0]);

    expect(component.featureFlags[0].enabled).toBeFalse();
    expect(toast.error).toHaveBeenCalledWith('Could not update the flag.');
  });

  it('uploads a logo then re-reads the settings for the cache-busted URL', () => {
    const uploaded = { ...settings, logoUrl: '/api/v1/public/platform/logo?v=99' };
    admin.uploadPlatformLogo.and.returnValue(of({ logoUrl: uploaded.logoUrl! }));
    admin.getPlatformSettings.and.returnValue(of(uploaded));
    const file = new File(['x'], 'logo.png', { type: 'image/png' });

    component.onLogoSelected(file);

    expect(admin.uploadPlatformLogo).toHaveBeenCalledWith(file);
    expect(component.logoSrc).toBe('http://localhost:3000/api/v1/public/platform/logo?v=99');
    expect(component.uploading).toBeFalse();
    expect(toast.success).toHaveBeenCalledWith('Logo uploaded.');
  });

  it('reports a rejected logo without calling the server', () => {
    component.onLogoRejected('huge.png is 5.0MB; the limit is 2MB.');

    expect(admin.uploadPlatformLogo).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('huge.png is 5.0MB; the limit is 2MB.');
  });
});
