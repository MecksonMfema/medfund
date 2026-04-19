import { Injectable } from '@angular/core';

export interface TenantBranding {
  templateId: string;
  logoUrl?: string;
  /** Overrides the template's main action color (buttons, links, badges). */
  primaryColor?: string;
  /** Overrides the template's sidebar accent / active indicator color. */
  accentColor?: string;
  /** Overrides the template's sidebar background color. */
  sidebarBg?: string;
}

export interface TenantTemplate {
  id: string;
  name: string;
  description: string;
  /** [sidebar bg, accent, surface] — used for preview swatches */
  previewColors: [string, string, string];
  vars: Record<string, string>;
}

export const TENANT_TEMPLATES: TenantTemplate[] = [
  // ── Default: mirrors the platform admin light theme ──────────────────────
  {
    id: 'platform',
    name: 'Default (Light)',
    description: 'White sidebar matching the platform theme — minimal and familiar',
    previewColors: ['#FFFFFF', '#0077B6', '#F8FDFF'],
    vars: {
      '--ts-bg':               '#FFFFFF',
      '--ts-bg-hover':         'rgba(0, 119, 182, 0.06)',
      '--ts-bg-active':        'rgba(0, 119, 182, 0.08)',
      '--ts-accent':           '#0077B6',
      '--ts-text':             '#4B5563',
      '--ts-text-muted':       '#9CA3AF',
      '--ts-text-strong':      '#111827',
      '--ts-text-active':      '#0077B6',
      '--ts-border':           '#F3F4F6',
      '--ts-subtle-bg':        'rgba(0,0,0,0.04)',
      '--color-primary':       '#0077B6',
      '--color-primary-hover': '#005f8f',
      '--color-primary-light': 'rgba(0, 119, 182, 0.1)',
    },
  },
  // ── Dark themes ──────────────────────────────────────────────────────────
  {
    id: 'ocean',
    name: 'Ocean',
    description: 'Deep navy with teal accents — professional and calm',
    previewColors: ['#0f172a', '#14b8a6', '#f0fdfa'],
    vars: {
      '--ts-bg':               '#0f172a',
      '--ts-bg-hover':         '#1e293b',
      '--ts-bg-active':        'rgba(20, 184, 166, 0.15)',
      '--ts-accent':           '#14b8a6',
      '--ts-text':             '#cbd5e1',
      '--ts-text-muted':       '#64748b',
      '--ts-text-strong':      '#f1f5f9',
      '--ts-text-active':      '#14b8a6',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#0d9488',
      '--color-primary-hover': '#0f766e',
      '--color-primary-light': 'rgba(20, 184, 166, 0.1)',
    },
  },
  {
    id: 'forest',
    name: 'Forest',
    description: 'Rich forest green — natural and trustworthy',
    previewColors: ['#0f2318', '#22c55e', '#f0fdf4'],
    vars: {
      '--ts-bg':               '#0f2318',
      '--ts-bg-hover':         '#1a3a26',
      '--ts-bg-active':        'rgba(34, 197, 94, 0.15)',
      '--ts-accent':           '#22c55e',
      '--ts-text':             '#bbf7d0',
      '--ts-text-muted':       '#4ade80',
      '--ts-text-strong':      '#f0fdf4',
      '--ts-text-active':      '#22c55e',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#16a34a',
      '--color-primary-hover': '#15803d',
      '--color-primary-light': 'rgba(34, 197, 94, 0.1)',
    },
  },
  {
    id: 'midnight',
    name: 'Midnight',
    description: 'Deep purple with violet accents — bold and distinctive',
    previewColors: ['#1e1035', '#a855f7', '#faf5ff'],
    vars: {
      '--ts-bg':               '#1e1035',
      '--ts-bg-hover':         '#2d1a52',
      '--ts-bg-active':        'rgba(168, 85, 247, 0.15)',
      '--ts-accent':           '#a855f7',
      '--ts-text':             '#e9d5ff',
      '--ts-text-muted':       '#9333ea',
      '--ts-text-strong':      '#faf5ff',
      '--ts-text-active':      '#a855f7',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#9333ea',
      '--color-primary-hover': '#7e22ce',
      '--color-primary-light': 'rgba(168, 85, 247, 0.1)',
    },
  },
  {
    id: 'ember',
    name: 'Ember',
    description: 'Dark charcoal with warm amber — energetic and modern',
    previewColors: ['#1c1008', '#f59e0b', '#fffbeb'],
    vars: {
      '--ts-bg':               '#1c1008',
      '--ts-bg-hover':         '#2d1f0e',
      '--ts-bg-active':        'rgba(245, 158, 11, 0.15)',
      '--ts-accent':           '#f59e0b',
      '--ts-text':             '#fde68a',
      '--ts-text-muted':       '#d97706',
      '--ts-text-strong':      '#fffbeb',
      '--ts-text-active':      '#f59e0b',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#d97706',
      '--color-primary-hover': '#b45309',
      '--color-primary-light': 'rgba(245, 158, 11, 0.1)',
    },
  },
  {
    id: 'slate',
    name: 'Slate',
    description: 'Professional slate gray with sky blue — clean and corporate',
    previewColors: ['#0f1623', '#38bdf8', '#f0f9ff'],
    vars: {
      '--ts-bg':               '#0f1623',
      '--ts-bg-hover':         '#1e2a3a',
      '--ts-bg-active':        'rgba(56, 189, 248, 0.15)',
      '--ts-accent':           '#38bdf8',
      '--ts-text':             '#94a3b8',
      '--ts-text-muted':       '#475569',
      '--ts-text-strong':      '#f0f9ff',
      '--ts-text-active':      '#38bdf8',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#0284c7',
      '--color-primary-hover': '#0369a1',
      '--color-primary-light': 'rgba(56, 189, 248, 0.1)',
    },
  },
  {
    id: 'rose',
    name: 'Rose',
    description: 'Dark plum with rose accents — healthcare warmth',
    previewColors: ['#1f0a10', '#fb7185', '#fff1f2'],
    vars: {
      '--ts-bg':               '#1f0a10',
      '--ts-bg-hover':         '#36101a',
      '--ts-bg-active':        'rgba(251, 113, 133, 0.15)',
      '--ts-accent':           '#fb7185',
      '--ts-text':             '#fecdd3',
      '--ts-text-muted':       '#e11d48',
      '--ts-text-strong':      '#fff1f2',
      '--ts-text-active':      '#fb7185',
      '--ts-border':           'rgba(255,255,255,0.07)',
      '--ts-subtle-bg':        'rgba(255,255,255,0.05)',
      '--color-primary':       '#e11d48',
      '--color-primary-hover': '#be123c',
      '--color-primary-light': 'rgba(251, 113, 133, 0.1)',
    },
  },
];

const ALL_VAR_KEYS = [
  '--ts-bg', '--ts-bg-hover', '--ts-bg-active', '--ts-accent',
  '--ts-text', '--ts-text-muted', '--ts-text-strong', '--ts-text-active',
  '--ts-border', '--ts-subtle-bg',
  '--color-primary', '--color-primary-hover', '--color-primary-light',
];

@Injectable({ providedIn: 'root' })
export class BrandingService {
  getTemplates(): TenantTemplate[] {
    return TENANT_TEMPLATES;
  }

  parseBranding(raw: string | null | undefined): TenantBranding {
    if (!raw || raw === '{}') return { templateId: 'platform' };
    try {
      const parsed = JSON.parse(raw);
      return { templateId: 'platform', ...parsed };
    } catch {
      return { templateId: 'platform' };
    }
  }

  /** Applies the branding CSS variables to a DOM element (cascades to all children). */
  apply(element: HTMLElement, branding: TenantBranding): void {
    const template = TENANT_TEMPLATES.find(t => t.id === branding.templateId) ?? TENANT_TEMPLATES[0];
    const vars = { ...template.vars };

    // Per-tenant color overrides — only applied when value is a valid hex color
    const sidebarBg     = this.validHex(branding.sidebarBg);
    const accentColor   = this.validHex(branding.accentColor);
    const primaryColor  = this.validHex(branding.primaryColor);

    if (sidebarBg) {
      vars['--ts-bg']       = sidebarBg;
      vars['--ts-bg-hover'] = this.lighten(sidebarBg, 12);
    }
    if (accentColor) {
      vars['--ts-accent']      = accentColor;
      vars['--ts-text-active'] = accentColor;
      vars['--ts-bg-active']   = this.hexToRgba(accentColor, 0.15);
    }
    if (primaryColor) {
      vars['--color-primary']       = primaryColor;
      vars['--color-primary-hover'] = this.darken(primaryColor, 15);
      vars['--color-primary-light'] = this.hexToRgba(primaryColor, 0.1);
    }

    for (const [key, value] of Object.entries(vars)) {
      element.style.setProperty(key, value);
    }
  }

  /** Returns the value only if it is a valid 3- or 6-digit hex color, otherwise null. */
  private validHex(value: string | undefined): string | null {
    if (!value) return null;
    return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value.trim()) ? value.trim() : null;
  }

  /** Removes all tenant CSS variables (called when leaving the tenant portal). */
  reset(element: HTMLElement): void {
    for (const key of ALL_VAR_KEYS) {
      element.style.removeProperty(key);
    }
  }

  private hexToRgb(hex: string): [number, number, number] | null {
    const r = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    return r ? [parseInt(r[1], 16), parseInt(r[2], 16), parseInt(r[3], 16)] : null;
  }

  private hexToRgba(hex: string, alpha: number): string {
    const rgb = this.hexToRgb(hex);
    if (!rgb) return `rgba(0,0,0,${alpha})`;
    return `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${alpha})`;
  }

  /** Lightens a hex color by adding `amount` to each channel. */
  private lighten(hex: string, amount: number): string {
    const rgb = this.hexToRgb(hex);
    if (!rgb) return hex;
    return '#' + rgb.map(c => Math.min(255, c + amount).toString(16).padStart(2, '0')).join('');
  }

  /** Darkens a hex color by subtracting `amount` from each channel. */
  private darken(hex: string, amount: number): string {
    const rgb = this.hexToRgb(hex);
    if (!rgb) return hex;
    return '#' + rgb.map(c => Math.max(0, c - amount).toString(16).padStart(2, '0')).join('');
  }
}
