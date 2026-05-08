import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DataTableComponent, TableAction } from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import {
  CreateRulePayload,
  RULE_CATEGORIES,
  RuleCategory,
  RuleTemplate,
  RulesService,
  TenantRule,
  UpdateRulePayload,
} from '../../../core/services/rules.service';
import { RuleEditorComponent } from './rule-editor/rule-editor.component';
import { RuleDryRunComponent } from './rule-dry-run/rule-dry-run.component';

/**
 * Rules Engine landing page.
 *
 * Layout:
 *   Left  — six-category sidebar (eligibility, waiting period, benefit limit,
 *           pre-auth, tariff, clinical) with rule counts.
 *   Main  — table of rules in the selected category. Empty state with a
 *           "New Rule" CTA when no rules in that category.
 *   Modals — template picker (for creates), editor (for create/edit),
 *           dry-run sandbox (for testing).
 */
@Component({
  selector: 'app-tenant-rules',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    DataTableComponent,
    RuleEditorComponent,
    RuleDryRunComponent,
  ],
  templateUrl: './rules.component.html',
  styleUrl: './rules.component.scss',
})
export class TenantRulesComponent implements OnInit {
  readonly categories = RULE_CATEGORIES;
  selectedCategory: RuleCategory = 'ELIGIBILITY';

  rules: TenantRule[] = [];
  loading = false;

  /**
   * Cached count per category — populated on first load + refreshed on every CRUD.
   * Built from {@link RULE_CATEGORIES} so adding a new category in one place
   * stays in sync here automatically.
   */
  countsByCategory: Record<RuleCategory, number> = this.emptyCounts();

  // Template picker
  showTemplatePicker = false;
  templates: RuleTemplate[] = [];
  templatesLoading = false;
  templateSearch = '';

  // Editor
  showEditor = false;
  editorMode: 'create' | 'edit' = 'create';
  editingRule?: TenantRule;
  editorSeed?: RuleTemplate;

  // Dry-run
  showDryRun = false;
  dryRunRule?: TenantRule;

  columns = [
    { key: 'name',        label: 'Name' },
    { key: 'ruleKey',     label: 'Key' },
    { key: 'priority',    label: 'Priority' },
    { key: 'enabledLabel', label: 'Status', type: 'status' },
    { key: 'updatedAt',   label: 'Last updated', type: 'date' },
  ];

  actions: TableAction[] = [
    { label: 'Edit',     icon: 'edit',         color: 'default',                                          handler: (r) => this.openEdit(r) },
    { label: 'Dry-run',  icon: 'play-circle',  color: 'default',                                          handler: (r) => this.openDryRun(r) },
    { label: 'Disable',  icon: 'pause-circle', color: 'warning', visible: (r) => !!r.enabled,             handler: (r) => this.toggle(r, false) },
    { label: 'Enable',   icon: 'play-circle',  color: 'success', visible: (r) => !r.enabled,              handler: (r) => this.toggle(r, true) },
    { label: 'Delete',   icon: 'x-circle',     color: 'danger',                                            handler: (r) => this.confirmDelete(r) },
  ];

  constructor(private rulesService: RulesService) {}

  ngOnInit(): void {
    this.loadCategory(this.selectedCategory);
    this.refreshCounts();
  }

  // ── Category selection ────────────────────────────────────────────────────

  selectCategory(c: RuleCategory): void {
    this.selectedCategory = c;
    this.loadCategory(c);
  }

  loadCategory(category: RuleCategory): void {
    this.loading = true;
    this.rulesService.list(category).subscribe({
      next: (rules) => {
        this.rules = rules.map(r => ({
          ...r,
          enabledLabel: r.enabled ? 'enabled' : 'disabled',
        }) as TenantRule);
        this.countsByCategory[category] = rules.length;
        this.loading = false;
      },
      error: () => { this.rules = []; this.loading = false; },
    });
  }

  /**
   * Pull all rules once (no category filter) to refresh the per-category
   * counts shown on the left sidebar. Cheap — rules per tenant are rarely
   * more than a few hundred and the response is small.
   */
  refreshCounts(): void {
    this.rulesService.list().subscribe({
      next: (rules) => {
        const next = this.emptyCounts();
        for (const r of rules) {
          if (next[r.category] !== undefined) next[r.category]++;
        }
        this.countsByCategory = next;
      },
      error: () => {},
    });
  }

  /** Builds a zero-valued counts map from RULE_CATEGORIES — single source of truth. */
  private emptyCounts(): Record<RuleCategory, number> {
    return RULE_CATEGORIES.reduce((acc, c) => {
      acc[c.id] = 0;
      return acc;
    }, {} as Record<RuleCategory, number>);
  }

  // ── Create flow ───────────────────────────────────────────────────────────

  openTemplatePicker(): void {
    this.showTemplatePicker = true;
    this.templateSearch = '';
    if (!this.templates.length) {
      this.templatesLoading = true;
      this.rulesService.getTemplates().subscribe({
        next: (t) => { this.templates = t; this.templatesLoading = false; },
        error: () => { this.templatesLoading = false; },
      });
    }
  }

  get filteredTemplates(): RuleTemplate[] {
    const q = this.templateSearch.trim().toLowerCase();
    const inCategory = this.templates.filter(t => t.category === this.selectedCategory);
    if (!q) return inCategory;
    return inCategory.filter(t =>
      t.name.toLowerCase().includes(q) ||
      (t.description || '').toLowerCase().includes(q));
  }

  pickTemplate(t: RuleTemplate): void {
    this.showTemplatePicker = false;
    this.editorSeed = t;
    this.editingRule = undefined;
    this.editorMode = 'create';
    this.showEditor = true;
  }

  startBlankRule(): void {
    this.showTemplatePicker = false;
    this.editorSeed = undefined;
    this.editingRule = undefined;
    this.editorMode = 'create';
    this.showEditor = true;
  }

  // ── Edit / save / delete / toggle ─────────────────────────────────────────

  openEdit(rule: TenantRule): void {
    this.editingRule = rule;
    this.editorSeed = undefined;
    this.editorMode = 'edit';
    this.showEditor = true;
  }

  onEditorSaved(saved: TenantRule): void {
    this.showEditor = false;
    if (saved.category === this.selectedCategory) {
      this.loadCategory(this.selectedCategory);
    } else {
      // category changed — switch to the new one to show the saved rule
      this.selectCategory(saved.category);
    }
    this.refreshCounts();
  }

  onEditorCancelled(): void {
    this.showEditor = false;
  }

  onEditorCreate(payload: CreateRulePayload): void {
    this.rulesService.create(payload).subscribe({
      next: (saved) => this.onEditorSaved(saved),
      error: () => {/* handled inside editor */},
    });
  }

  onEditorUpdate(event: { id: string; payload: UpdateRulePayload }): void {
    this.rulesService.update(event.id, event.payload).subscribe({
      next: (saved) => this.onEditorSaved(saved),
      error: () => {/* handled inside editor */},
    });
  }

  toggle(rule: TenantRule, enable: boolean): void {
    const op = enable ? this.rulesService.enable(rule.id) : this.rulesService.disable(rule.id);
    op.subscribe({
      next: () => { this.loadCategory(this.selectedCategory); this.refreshCounts(); },
      error: () => {},
    });
  }

  confirmDelete(rule: TenantRule): void {
    if (!confirm(`Delete rule "${rule.name}"? This cannot be undone.`)) return;
    this.rulesService.delete(rule.id).subscribe({
      next: () => { this.loadCategory(this.selectedCategory); this.refreshCounts(); },
      error: () => {},
    });
  }

  // ── Dry-run ───────────────────────────────────────────────────────────────

  openDryRun(rule: TenantRule): void {
    this.dryRunRule = rule;
    this.showDryRun = true;
  }

  closeDryRun(): void {
    this.showDryRun = false;
    this.dryRunRule = undefined;
  }

  // ── Utility ───────────────────────────────────────────────────────────────

  trackCategory(_: number, c: { id: string }): string {
    return c.id;
  }

  /** Friendly label of the currently-selected category, used in the picker hint + table header. */
  get selectedCategoryLabel(): string {
    return this.categories.find(c => c.id === this.selectedCategory)?.label ?? this.selectedCategory;
  }
}
