import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { switchMap } from 'rxjs/operators';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { LogoUploaderComponent } from '../../../shared/components/logo-uploader/logo-uploader.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import {
  AdminService,
  PlatformFeatureFlag,
  PlatformSettings,
  PlatformSettingsPatch,
} from '../../../core/services/admin.service';
import { BrandingService, TenantTemplate } from '../../../core/services/branding.service';
import { FeatureFlagService } from '../../../core/services/feature-flag.service';
import { PlatformThemeService } from '../../../core/services/platform-theme.service';
import { extractErrorMessage } from '../../../core/util/http-errors';

/**
 * Super-admin configuration for the platform-wide identity, appearance, and
 * feature toggles. Backed by the singleton {@code public.platform_settings}
 * row and the {@code public.platform_feature_flags} catalogue in
 * tenancy-service; every save emits an audit event server-side.
 */
@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, LogoUploaderComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit {
  activeTab = 'general';

  tabs = [
    { id: 'general', label: 'General', icon: 'settings' },
    { id: 'appearance', label: 'Appearance', icon: 'globe' },
    { id: 'features', label: 'Feature Flags', icon: 'check-circle' },
  ];

  loading = true;
  loadError: string | null = null;
  saving = false;
  uploading = false;

  // General
  platformName = '';
  supportEmail = '';
  /** Absolute URL of the logo on file, or null when none has been uploaded. */
  logoSrc: string | null = null;
  /** Server-relative form of the same URL, kept so live previews do not drop it. */
  private logoUrlRaw: string | null = null;

  // Appearance
  selectedThemeId = 'platform';
  darkMode = false;
  heroTitle = '';
  heroSubtitle = '';

  /** The same colour templates tenants pick from, reused as the platform base layer. */
  themes: TenantTemplate[] = [];

  featureFlags: PlatformFeatureFlag[] = [];

  constructor(
    private adminService: AdminService,
    private brandingService: BrandingService,
    private platformTheme: PlatformThemeService,
    private featureFlagService: FeatureFlagService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.themes = this.brandingService.getTemplates();

    this.adminService.getPlatformSettings().subscribe({
      next: (settings) => {
        this.hydrate(settings);
        this.loading = false;
      },
      error: (err) => {
        this.loadError = extractErrorMessage(err, 'Could not load platform settings.');
        this.loading = false;
      },
    });

    this.adminService.getFeatureFlags().subscribe({
      next: (flags) => {
        this.featureFlags = flags;
        this.featureFlagService.ingest(flags);
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Could not load feature flags.'));
      },
    });
  }

  saveGeneral(): void {
    this.save(
      { platformName: this.platformName, supportEmail: this.supportEmail },
      'Platform identity saved.',
    );
  }

  saveAppearance(): void {
    this.save(
      { themeTemplateId: this.selectedThemeId, darkMode: this.darkMode },
      'Appearance saved.',
    );
  }

  saveLandingCopy(): void {
    this.save(
      { heroTitle: this.heroTitle, heroSubtitle: this.heroSubtitle },
      'Landing page copy saved.',
    );
  }

  /** Live preview: the picker re-skins the portal before the save lands, so
   *  the admin sees what they are committing to. A reload without a save
   *  reverts to the persisted theme. */
  selectTheme(templateId: string): void {
    this.selectedThemeId = templateId;
    this.previewAppearance();
  }

  onDarkModeToggled(): void {
    this.previewAppearance();
  }

  onLogoSelected(file: File): void {
    this.uploading = true;
    // The upload endpoint persists the bytes itself, so the follow-up GET is
    // only to pick up the new cache-busted URL and refresh the preview.
    this.adminService.uploadPlatformLogo(file).pipe(
      switchMap(() => this.adminService.getPlatformSettings()),
    ).subscribe({
      next: (settings) => {
        this.hydrate(settings);
        this.platformTheme.applyFromSettings(settings);
        this.toast.success('Logo uploaded.');
        this.uploading = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Logo upload failed.'));
        this.uploading = false;
      },
    });
  }

  onLogoRejected(reason: string): void {
    this.toast.error(reason);
  }

  toggleFlag(flag: PlatformFeatureFlag): void {
    const desired = !flag.enabled;
    this.adminService.updateFeatureFlag(flag.key, desired).subscribe({
      next: (updated) => {
        flag.enabled = updated.enabled;
        this.featureFlagService.ingest(this.featureFlags);
        this.toast.success(`${flag.name} ${updated.enabled ? 'enabled' : 'disabled'}.`);
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Could not update the flag.'));
      },
    });
  }

  private save(patch: PlatformSettingsPatch, successMessage: string): void {
    this.saving = true;
    this.adminService.updatePlatformSettings(patch).subscribe({
      next: (settings) => {
        this.hydrate(settings);
        this.platformTheme.applyFromSettings(settings);
        this.toast.success(successMessage);
        this.saving = false;
      },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Save failed.'));
        this.saving = false;
      },
    });
  }

  private hydrate(settings: PlatformSettings): void {
    this.platformName = settings.platformName ?? '';
    this.supportEmail = settings.supportEmail ?? '';
    this.selectedThemeId = settings.themeTemplateId ?? 'platform';
    this.darkMode = settings.darkMode === true;
    this.heroTitle = settings.heroTitle ?? '';
    this.heroSubtitle = settings.heroSubtitle ?? '';
    this.logoUrlRaw = settings.logoUrl;
    this.logoSrc = this.platformTheme.logoSrc(settings.logoUrl);
  }

  private previewAppearance(): void {
    this.platformTheme.applyFromSettings({
      platformName: this.platformName,
      logoUrl: this.logoUrlRaw,
      themeTemplateId: this.selectedThemeId,
      darkMode: this.darkMode,
      heroTitle: this.heroTitle,
      heroSubtitle: this.heroSubtitle,
    });
  }
}
