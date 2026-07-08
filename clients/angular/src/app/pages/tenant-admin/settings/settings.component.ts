import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { EditorComponent } from '@tinymce/tinymce-angular';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import {
  AdminService,
  Tenant as AdminTenant,
  TenantEmailTemplate,
} from '../../../core/services/admin.service';
import { TenantRolesTabComponent } from './roles/roles-tab.component';
import { TenantCurrenciesTabComponent } from './currencies/currencies-tab.component';
import { TenantBillingTabComponent } from './billing/billing-tab.component';
import { TenantProrationTabComponent } from './proration/proration-tab.component';
import { TenantService } from '../../../core/services/tenant.service';
import {
  BrandingService,
  TenantBranding,
  TenantTemplate,
  TENANT_TEMPLATES,
} from '../../../core/services/branding.service';
import {
  INSURANCE_LINES,
  InsuranceLine,
  parseInsuranceLines,
  parseProviderRegLabel,
  deriveProviderRegLabel,
  parseSchemeTerminology,
  parseDrugClaimsEnabled,
  DEFAULT_SCHEME_TERMINOLOGY,
  SCHEME_TERMINOLOGY_PRESETS,
  SchemeTerminology,
} from '../../../core/models/insurance-lines';

interface GeneralForm {
  name: string;
  domain: string;
  contactEmail: string;
  timezone: string;
  membershipModel: string;
}

const MEMBERSHIP_MODELS = [
  { value: 'INDIVIDUAL_ONLY', label: 'Individual members only' },
  { value: 'GROUP_ONLY',      label: 'Group / employer-sponsored only' },
  { value: 'BOTH',            label: 'Both individual and group' },
];

type TabId = 'general' | 'branding' | 'insurance-lines' | 'currencies' | 'billing' | 'proration' | 'email-templates' | 'roles';

interface Tab {
  id: TabId;
  label: string;
  icon: string;
}

/**
 * Tenant settings — replaces the legacy single-page "Administration" screen.
 * Each section the IT admin uses to customise their portal lives behind a
 * tab in this one component. Branding has been migrated unchanged from
 * {@code AdminComponent}; the remaining tabs (general / insurance-lines /
 * email-templates) are populated in follow-up tasks.
 */
@Component({
  selector: 'app-tenant-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, EditorComponent, TenantRolesTabComponent, TenantCurrenciesTabComponent, TenantBillingTabComponent, TenantProrationTabComponent, SelectComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class TenantSettingsComponent implements OnInit {
  activeTab: TabId = 'general';

  tabs: Tab[] = [
    { id: 'general',         label: 'General',                icon: 'settings' },
    { id: 'branding',        label: 'Branding',               icon: 'globe' },
    { id: 'insurance-lines', label: 'Insurance Lines',        icon: 'briefcase' },
    { id: 'currencies',      label: 'Currencies',             icon: 'dollar-sign' },
    { id: 'billing',         label: 'Billing',                icon: 'banknote' },
    { id: 'proration',       label: 'Proration',              icon: 'divide' },
    { id: 'email-templates', label: 'Email Templates',        icon: 'file-text' },
    { id: 'roles',           label: 'Roles & Permissions',    icon: 'shield' },
  ];

  // ── Branding state (lifted verbatim from AdminComponent) ──────────────────
  templates: TenantTemplate[] = TENANT_TEMPLATES;
  branding: TenantBranding = { templateId: 'platform' };
  brandingSaving = false;
  brandingSaved = false;

  // ── General state ─────────────────────────────────────────────────────────
  membershipModels = MEMBERSHIP_MODELS;
  general: GeneralForm = { name: '', domain: '', contactEmail: '', timezone: '', membershipModel: '' };
  /** Read-only fields exposed to the form for display. */
  generalReadonly = { slug: '', countryCode: '', status: '' };
  generalLoading = false;
  generalSaving = false;
  generalSaved = false;
  generalError: string | null = null;

  /**
   * TinyMCE init options for the email-body editor. We deliberately keep the
   * toolbar focused on email-friendly formatting (no media embed, no advanced
   * tables) — and we leave variables like {{user.firstName}} alone since they
   * are interpolated server-side at send time.
   */
  readonly tinymceInit = {
    base_url: '/tinymce',
    suffix: '.min',
    license_key: 'gpl',
    height: 360,
    menubar: false,
    branding: false,
    promotion: false,
    plugins: 'lists link autolink code image preview help wordcount',
    toolbar:
      'undo redo | blocks | bold italic underline | forecolor backcolor | ' +
      'alignleft aligncenter alignright | bullist numlist | link image | ' +
      'removeformat | code preview',
    block_formats: 'Paragraph=p; Heading 1=h1; Heading 2=h2; Heading 3=h3; Quote=blockquote',
    /** Stop TinyMCE from clobbering Mustache placeholders during cleanup. */
    extended_valid_elements: '*[*]',
    valid_children: '+body[style]',
    /** Keep the editor's internal styles isolated from the host page. */
    content_style:
      'body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; ' +
      'font-size: 14px; line-height: 1.5; color: #1f2937; }',
  };

  // ── Email templates state ─────────────────────────────────────────────────
  emailTemplates: TenantEmailTemplate[] = [];
  emailTemplatesLoading = false;
  emailTemplatesError: string | null = null;
  /** Currently expanded template key — only one is editable at a time. */
  editingEmailKey: string | null = null;
  /** Working copy of the expanded template — discarded on cancel. */
  emailDraft: { subject: string; htmlBody: string; textBody: string } = { subject: '', htmlBody: '', textBody: '' };
  emailSaving = false;
  emailSavingKey: string | null = null;

  // ── Insurance lines state ─────────────────────────────────────────────────
  insuranceLineOptions: InsuranceLine[] = INSURANCE_LINES;
  selectedLines: string[] = [];
  providerRegLabel = '';
  /** Snapshot taken from the loaded tenant's settings — used to detect dirty state. */
  private originalLines: string[] = [];
  private originalProviderRegLabel = '';
  insuranceLoading = false;
  insuranceSaving = false;
  insuranceSaved = false;
  insuranceError: string | null = null;

  // ── Scheme terminology state ──────────────────────────────────────────────
  schemeTerminologyPresets = SCHEME_TERMINOLOGY_PRESETS;
  /** Selected preset id, or 'custom' when the admin types their own labels. */
  schemeTerminologyId = 'scheme';
  schemeTerminology: SchemeTerminology = { ...DEFAULT_SCHEME_TERMINOLOGY };
  private originalSchemeTerminologyId = 'scheme';
  private originalSchemeTerminology: SchemeTerminology = { ...DEFAULT_SCHEME_TERMINOLOGY };

  // ── Drug-claims toggle ───────────────────────────────────────────────────
  drugClaimsEnabled = true;
  private originalDrugClaimsEnabled = true;

  // ── SelectComponent options ─────────────────────────────────────────────
  /** Membership-model picker — blank-leading row preserves the placeholder slot. */
  get membershipModelSelectOptions(): SelectOption[] {
    return this.membershipModels.map(m => ({ value: m.value, label: m.label }));
  }
  /** Scheme-terminology presets plus a free-form 'Custom…' bucket. */
  get schemeTerminologySelectOptions(): SelectOption[] {
    return [
      ...this.schemeTerminologyPresets.map(p => ({ value: p.id, label: `${p.singular} / ${p.plural}` })),
      { value: 'custom', label: 'Custom…' },
    ];
  }

  constructor(
    private adminService: AdminService,
    private tenantService: TenantService,
    private brandingService: BrandingService,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant?.branding) {
      this.branding = { ...tenant.branding };
    }
    if (tenant?.id) {
      this.loadGeneral(tenant.id);
      this.loadInsuranceLines(tenant.id);
      this.loadEmailTemplates(tenant.id);
    }
  }

  // ── Email templates ───────────────────────────────────────────────────────

  private loadEmailTemplates(tenantId: string): void {
    this.emailTemplatesLoading = true;
    this.emailTemplatesError   = null;
    this.adminService.getTenantEmailTemplates(tenantId).subscribe({
      next: (list) => {
        this.emailTemplates        = list;
        this.emailTemplatesLoading = false;
      },
      error: () => {
        this.emailTemplatesError   = 'Could not load email templates.';
        this.emailTemplatesLoading = false;
      },
    });
  }

  startEditingEmail(t: TenantEmailTemplate): void {
    this.editingEmailKey = t.key;
    this.emailDraft = {
      // Pre-populate from the override when one exists, otherwise from the
      // platform default — gives the admin a starting point to tweak.
      subject:  t.overridden ? t.subject  : t.defaultSubject,
      htmlBody: t.overridden ? t.htmlBody : t.defaultHtmlBody,
      textBody: t.overridden ? (t.textBody  ?? '') : (t.defaultTextBody ?? ''),
    };
  }

  cancelEditingEmail(): void {
    this.editingEmailKey = null;
  }

  saveEmailTemplate(t: TenantEmailTemplate): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;

    this.emailSaving    = true;
    this.emailSavingKey = t.key;
    this.adminService.upsertTenantEmailTemplate(tenant.id, t.key, {
      subject:  this.emailDraft.subject.trim(),
      htmlBody: this.emailDraft.htmlBody,
      textBody: this.emailDraft.textBody.trim() || undefined,
      enabled:  true,
    }).subscribe({
      next: (updated) => {
        // Replace the row in-place so the rest of the list stays intact.
        this.emailTemplates = this.emailTemplates.map(x => x.key === t.key ? updated : x);
        this.editingEmailKey = null;
        this.emailSaving     = false;
        this.emailSavingKey  = null;
      },
      error: (err) => {
        this.emailTemplatesError = err?.error?.detail || 'Could not save template.';
        this.emailSaving         = false;
        this.emailSavingKey      = null;
      },
    });
  }

  resetEmailTemplate(t: TenantEmailTemplate): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant || !t.overridden) return;

    this.emailSaving    = true;
    this.emailSavingKey = t.key;
    this.adminService.resetTenantEmailTemplate(tenant.id, t.key).subscribe({
      next: () => {
        // Reset the row to defaults-only without a full reload.
        this.emailTemplates = this.emailTemplates.map(x => x.key === t.key ? {
          ...x,
          overridden: false, enabled: true,
          subject: x.defaultSubject, htmlBody: x.defaultHtmlBody, textBody: x.defaultTextBody,
          id: undefined, version: undefined, updatedAt: undefined,
        } : x);
        if (this.editingEmailKey === t.key) this.editingEmailKey = null;
        this.emailSaving    = false;
        this.emailSavingKey = null;
      },
      error: (err) => {
        this.emailTemplatesError = err?.error?.detail || 'Could not reset template.';
        this.emailSaving         = false;
        this.emailSavingKey      = null;
      },
    });
  }

  // ── General ───────────────────────────────────────────────────────────────

  private loadGeneral(tenantId: string): void {
    this.generalLoading = true;
    this.generalError   = null;
    this.adminService.getTenantById(tenantId).subscribe({
      next: (t: AdminTenant) => {
        this.general = {
          name:            t.name            ?? '',
          domain:          t.domain          ?? '',
          contactEmail:    t.contactEmail    ?? '',
          timezone:        t.timezone        ?? '',
          membershipModel: t.membershipModel ?? '',
        };
        this.generalReadonly = {
          slug:        t.slug        ?? '',
          countryCode: t.countryCode ?? '',
          status:      t.status      ?? '',
        };
        this.generalLoading = false;
      },
      error: () => {
        this.generalError   = 'Could not load tenant details. Refresh to try again.';
        this.generalLoading = false;
      },
    });
  }

  // ── Insurance lines ───────────────────────────────────────────────────────

  private loadInsuranceLines(tenantId: string): void {
    this.insuranceLoading = true;
    this.insuranceError   = null;
    this.adminService.getTenantById(tenantId).subscribe({
      next: (t: AdminTenant) => {
        const lines = parseInsuranceLines(t.settings);
        const label = parseProviderRegLabel(t.settings);
        const term  = parseSchemeTerminology(t.settings);
        const drug  = parseDrugClaimsEnabled(t.settings);
        this.selectedLines           = [...lines];
        this.providerRegLabel        = label;
        this.schemeTerminology       = { ...term };
        this.drugClaimsEnabled       = drug;
        // Match against a preset to pre-select the dropdown; falls through
        // to 'custom' when no preset matches the saved labels.
        const matched = SCHEME_TERMINOLOGY_PRESETS.find(p =>
          p.singular === term.singular && p.plural === term.plural);
        this.schemeTerminologyId = matched?.id ?? 'custom';
        this.originalLines              = [...lines];
        this.originalProviderRegLabel   = label;
        this.originalSchemeTerminology  = { ...term };
        this.originalSchemeTerminologyId = this.schemeTerminologyId;
        this.originalDrugClaimsEnabled  = drug;
        this.insuranceLoading           = false;
      },
      error: () => {
        this.insuranceError   = 'Could not load insurance lines.';
        this.insuranceLoading = false;
      },
    });
  }

  // ── Scheme terminology ────────────────────────────────────────────────────

  onSchemeTerminologyPreset(id: string): void {
    this.schemeTerminologyId = id;
    if (id === 'custom') return; // leave inputs as-is for editing
    const preset = SCHEME_TERMINOLOGY_PRESETS.find(p => p.id === id);
    if (preset) this.schemeTerminology = { singular: preset.singular, plural: preset.plural };
  }

  schemeTerminologyDirty(): boolean {
    return (
      this.schemeTerminologyId !== this.originalSchemeTerminologyId ||
      this.schemeTerminology.singular !== this.originalSchemeTerminology.singular ||
      this.schemeTerminology.plural   !== this.originalSchemeTerminology.plural
    );
  }

  toggleInsuranceLine(value: string): void {
    const idx = this.selectedLines.indexOf(value);
    if (idx === -1) {
      this.selectedLines = [...this.selectedLines, value];
    } else {
      this.selectedLines = this.selectedLines.filter(v => v !== value);
    }
  }

  isInsuranceLineSelected(value: string): boolean {
    return this.selectedLines.includes(value);
  }

  insuranceLinesDirty(): boolean {
    if (this.selectedLines.length !== this.originalLines.length) return true;
    if (this.selectedLines.some(v => !this.originalLines.includes(v))) return true;
    if (this.providerRegLabel.trim() !== this.originalProviderRegLabel.trim()) return true;
    if (this.drugClaimsEnabled !== this.originalDrugClaimsEnabled) return true;
    return this.schemeTerminologyDirty();
  }

  saveInsuranceLines(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;
    if (this.selectedLines.length === 0) {
      this.insuranceError = 'Select at least one insurance line.';
      return;
    }
    const singular = this.schemeTerminology.singular.trim();
    const plural   = this.schemeTerminology.plural.trim();
    if (!singular || !plural) {
      this.insuranceError = 'Scheme terminology singular and plural are both required.';
      return;
    }

    this.insuranceSaving = true;
    this.insuranceSaved  = false;
    this.insuranceError  = null;

    // Insurance lines, provider-reg label, and scheme terminology all live in
    // the settings JSONB — write atomically so a customised field isn't
    // orphaned by an unrelated change.
    const settings = JSON.stringify({
      insuranceLines:       this.selectedLines,
      providerRegLabel:     this.providerRegLabel.trim() || deriveProviderRegLabel(this.selectedLines),
      schemeLabelSingular:  singular,
      schemeLabelPlural:    plural,
      drugClaimsEnabled:    this.drugClaimsEnabled,
    });

    this.adminService.updateTenant(tenant.id, { settings }).subscribe({
      next: () => {
        this.originalLines               = [...this.selectedLines];
        this.originalProviderRegLabel    = this.providerRegLabel.trim();
        this.originalSchemeTerminology   = { singular, plural };
        this.originalSchemeTerminologyId = this.schemeTerminologyId;
        this.originalDrugClaimsEnabled   = this.drugClaimsEnabled;
        // Refresh the cached tenant so anything reading insuranceLines, scheme
        // labels, or feature flags reactively (sidebar, page titles) sees the
        // new values without a page reload.
        this.tenantService.setTenant({
          ...tenant,
          insuranceLines:       [...this.selectedLines],
          providerRegLabel:     this.providerRegLabel.trim() || deriveProviderRegLabel(this.selectedLines),
          schemeLabelSingular:  singular,
          schemeLabelPlural:    plural,
          drugClaimsEnabled:    this.drugClaimsEnabled,
        });
        this.insuranceSaving = false;
        this.insuranceSaved  = true;
        setTimeout(() => (this.insuranceSaved = false), 3000);
      },
      error: (err) => {
        this.insuranceError  = err?.error?.detail || 'Could not save insurance lines.';
        this.insuranceSaving = false;
      },
    });
  }

  saveGeneral(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;

    this.generalSaving = true;
    this.generalSaved  = false;
    this.generalError  = null;

    this.adminService.updateTenant(tenant.id, {
      name:            this.general.name.trim(),
      domain:          this.general.domain.trim() || undefined,
      contactEmail:    this.general.contactEmail.trim() || undefined,
      timezone:        this.general.timezone || undefined,
      membershipModel: this.general.membershipModel || undefined,
    }).subscribe({
      next: (updated) => {
        // Refresh the cached TenantService snapshot so the sidebar / dashboard
        // reflect any name or timezone change without a hard reload.
        this.tenantService.setTenant({
          ...tenant,
          name:     updated.name     ?? tenant.name,
          timezone: updated.timezone ?? tenant.timezone,
        });
        this.generalSaving = false;
        this.generalSaved  = true;
        setTimeout(() => (this.generalSaved = false), 3000);
      },
      error: (err) => {
        this.generalError  = err?.error?.detail || 'Could not save changes.';
        this.generalSaving = false;
      },
    });
  }

  // ── Branding ──────────────────────────────────────────────────────────────

  selectTemplate(id: string): void {
    this.branding = { ...this.branding, templateId: id };
    this.previewBranding();
  }

  onColorChange(): void {
    this.previewBranding();
  }

  clearColor(field: 'primaryColor' | 'accentColor' | 'sidebarBg'): void {
    this.branding = { ...this.branding, [field]: undefined };
    this.previewBranding();
  }

  clearLogo(): void {
    this.branding = { ...this.branding, logoUrl: undefined };
    this.logoError = null;
    this.previewBranding();
  }

  // ── Logo upload ──────────────────────────────────────────────────────────
  /** Hard cap on the in-DB logo size — base64 inflates ~33% over the raw bytes. */
  private static readonly MAX_LOGO_BYTES = 256 * 1024;
  private static readonly ALLOWED_LOGO_TYPES = ['image/png', 'image/jpeg', 'image/svg+xml', 'image/webp'];

  logoError: string | null = null;
  logoUploading = false;

  /** File-input change handler — reads the chosen image as a data URL. */
  onLogoFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file  = input.files?.[0];
    if (!file) return;

    // Reset the input so picking the same file again still fires change.
    input.value = '';
    this.logoError = null;

    if (!TenantSettingsComponent.ALLOWED_LOGO_TYPES.includes(file.type)) {
      this.logoError = 'Logo must be a PNG, JPEG, SVG, or WEBP image.';
      return;
    }
    if (file.size > TenantSettingsComponent.MAX_LOGO_BYTES) {
      this.logoError = `Logo must be smaller than ${Math.round(TenantSettingsComponent.MAX_LOGO_BYTES / 1024)} KB.`;
      return;
    }

    this.logoUploading = true;
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = typeof reader.result === 'string' ? reader.result : '';
      this.branding = { ...this.branding, logoUrl: dataUrl };
      this.logoUploading = false;
      this.previewBranding();
    };
    reader.onerror = () => {
      this.logoError = 'Could not read the image file. Please try a different one.';
      this.logoUploading = false;
    };
    reader.readAsDataURL(file);
  }

  saveBranding(): void {
    const tenant = this.tenantService.getTenant();
    if (!tenant) return;

    this.brandingSaving = true;
    this.brandingSaved  = false;

    const sanitized    = this.sanitizeBranding(this.branding);
    const brandingJson = JSON.stringify(sanitized);

    this.adminService.updateTenant(tenant.id, { branding: brandingJson }).subscribe({
      next: () => {
        this.tenantService.setTenant({ ...tenant, branding: sanitized });
        this.branding       = sanitized;
        this.brandingSaving = false;
        this.brandingSaved  = true;
        setTimeout(() => (this.brandingSaved = false), 3000);
      },
      error: () => { this.brandingSaving = false; },
    });
  }

  private previewBranding(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) this.tenantService.setTenant({ ...tenant, branding: this.branding });
  }

  private sanitizeBranding(b: TenantBranding): TenantBranding {
    return {
      templateId:   this.sanitizeText(b.templateId),
      logoUrl:      b.logoUrl      ? this.sanitizeUrl(b.logoUrl)        : undefined,
      primaryColor: b.primaryColor ? this.sanitizeColor(b.primaryColor) : undefined,
      accentColor:  b.accentColor  ? this.sanitizeColor(b.accentColor)  : undefined,
      sidebarBg:    b.sidebarBg    ? this.sanitizeColor(b.sidebarBg)    : undefined,
    };
  }

  private sanitizeColor(v: string): string | undefined {
    return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v.trim()) ? v.trim() : undefined;
  }

  private sanitizeUrl(v: string): string | undefined {
    const t = v.trim();
    // Accept https URLs (legacy admin-pasted hosted logos) AND data-URL images
    // (new upload path — logo is stored inline in the branding JSON).
    if (t.startsWith('https://')) return t;
    if (/^data:image\/(png|jpeg|jpg|svg\+xml|webp);base64,/.test(t)) return t;
    return undefined;
  }

  private sanitizeText(v: string): string {
    return v.replace(/<[^>]*>/g, '').trim();
  }
}
