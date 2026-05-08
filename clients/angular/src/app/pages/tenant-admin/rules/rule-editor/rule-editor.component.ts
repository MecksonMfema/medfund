import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import {
  Condition,
  ConditionGroup,
  CreateRulePayload,
  RULE_CATEGORIES,
  RuleCategory,
  RuleDefinition,
  RuleTemplate,
  TenantRule,
  UpdateRulePayload,
} from '../../../../core/services/rules.service';

const OPERATORS = [
  { id: 'EQUALS',                 label: 'equals' },
  { id: 'NOT_EQUALS',             label: 'not equals' },
  { id: 'GREATER_THAN',           label: '>' },
  { id: 'GREATER_THAN_OR_EQUALS', label: '>=' },
  { id: 'LESS_THAN',              label: '<' },
  { id: 'LESS_THAN_OR_EQUALS',    label: '<=' },
  { id: 'CONTAINS',               label: 'contains' },
  { id: 'IN',                     label: 'in' },
  { id: 'NOT_IN',                 label: 'not in' },
];

/**
 * Every {@code ActionType} the rules engine compiles. Adding a new action
 * here is the only Angular-side change needed to expose it in the editor —
 * the backend wires the same enum value to its emitter automatically.
 *
 * The {@code value} hint guides the editor: when present, the editor shows
 * a "Value" field with that placeholder; otherwise it stays hidden.
 */
const ACTION_TYPES: { id: string; label: string; description: string; valueHint?: string }[] = [
  // Claims pipeline
  { id: 'REJECT',               label: 'Reject claim',         description: 'Mark the claim REJECTED with the supplied code + message.' },
  { id: 'FLAG_FOR_REVIEW',      label: 'Flag for review',      description: 'Allow the claim through but flag it for an adjudicator.' },
  { id: 'WARN',                 label: 'Warn',                 description: 'Surface a warning to the operator without rejecting.' },
  { id: 'CAP_TO_TARIFF',        label: 'Cap to tariff',        description: 'Reduce the billed amount to the tariff schedule rate.' },
  { id: 'APPLY_COPAY',          label: 'Apply co-pay',         description: 'Record a percentage or fixed-amount co-pay against the claim.', valueHint: '20  or  FIXED:25' },

  // Member lifecycle
  { id: 'SET_AGE_GROUP',        label: 'Set age group',        description: 'Tag the member with a tenant-defined age band label.', valueHint: 'CHILD / ADULT / SENIOR' },
  { id: 'AUTO_RENEW',           label: 'Auto-renew',           description: 'Mark the membership as eligible for auto-renewal.' },
  { id: 'TERMINATE_MEMBERSHIP', label: 'Terminate membership', description: 'Auto-terminate the membership (e.g. for non-payment).' },
  { id: 'REQUIRE_UNDERWRITING', label: 'Require underwriting', description: 'Flag enrollment for human underwriter review at LOW/MED/HIGH severity.', valueHint: 'LOW / MED / HIGH' },
  { id: 'APPLY_LOADED_PREMIUM', label: 'Apply premium loading', description: 'Multiply the standard premium by a risk factor.', valueHint: '1.25 (= +25%)' },

  // Contributions
  { id: 'SET_PREMIUM',          label: 'Set premium',          description: 'Set the contribution amount for the period.', valueHint: '100.00' },
  { id: 'APPLY_LATE_FEE',       label: 'Apply late fee',       description: 'Append a late fee to the contribution.', valueHint: '25.00' },

  // Finance
  { id: 'SCHEDULE_PAYMENT_RUN', label: 'Schedule payment run', description: 'Trigger a provider payment run on the given date.', valueHint: 'TODAY  or  2026-05-15' },
  { id: 'WITHHOLD_PAYMENT',     label: 'Withhold payment',     description: 'Hold back a percentage of the run amount.', valueHint: '10  (= 10%)' },
  { id: 'MATCH_RECORDS',        label: 'Match records',        description: 'Mark a candidate match in reconciliation.' },
];

interface EditorFormState {
  name: string;
  ruleKey: string;
  description: string;
  category: RuleCategory;
  priority: number;
  enabled: boolean;
  conditionsOperator: 'AND' | 'OR';
  conditionRows: Array<{ field: string; operator: string; value: string }>;
  actionType: string;
  rejectionCode: string;
  /** Generic action parameter — see ACTION_TYPES.valueHint for shape per action type. */
  actionValue: string;
  message: string;
  drlOverride: string;
}

/**
 * Two-mode rule editor used for both create and edit. Visual mode drives a
 * structured {@link RuleDefinition}; DRL mode lets advanced authors paste raw
 * Drools and bypasses the visual form's compile path. Switching modes is
 * preserved through the save round-trip — both fields go to the backend.
 */
@Component({
  selector: 'app-rule-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './rule-editor.component.html',
  styleUrl: './rule-editor.component.scss',
})
export class RuleEditorComponent implements OnInit {
  @Input({ required: true }) mode!: 'create' | 'edit';
  @Input() rule?: TenantRule;
  @Input() seed?: RuleTemplate;
  @Input() defaultCategory: RuleCategory = 'ELIGIBILITY';

  @Output() create = new EventEmitter<CreateRulePayload>();
  @Output() update = new EventEmitter<{ id: string; payload: UpdateRulePayload }>();
  @Output() cancel = new EventEmitter<void>();

  readonly categories = RULE_CATEGORIES;
  readonly operators = OPERATORS;
  readonly actionTypes = ACTION_TYPES;

  editorMode: 'visual' | 'drl' = 'visual';
  saving = false;
  serverError = '';

  form: EditorFormState = this.blankForm();

  ngOnInit(): void {
    if (this.mode === 'edit' && this.rule) {
      this.form = this.formFromRule(this.rule);
      // If an admin saved a DRL override, default to that mode so we don't
      // silently overwrite their custom DRL on first save.
      if (this.rule.drlOverride && this.rule.drlOverride.trim().length) {
        this.editorMode = 'drl';
      }
    } else if (this.mode === 'create' && this.seed) {
      this.form = this.formFromTemplate(this.seed);
    } else {
      this.form = { ...this.blankForm(), category: this.defaultCategory };
    }
  }

  // ── Form mutation helpers ────────────────────────────────────────────────

  addCondition(): void {
    this.form.conditionRows.push({ field: '', operator: 'EQUALS', value: '' });
  }

  removeCondition(index: number): void {
    this.form.conditionRows.splice(index, 1);
    if (this.form.conditionRows.length === 0) this.addCondition();
  }

  // ── Save ─────────────────────────────────────────────────────────────────

  submit(): void {
    this.serverError = '';
    const validation = this.validate();
    if (validation) { this.serverError = validation; return; }

    const payload = this.toPayload();
    this.saving = true;

    if (this.mode === 'create') {
      this.create.emit(payload as CreateRulePayload);
    } else if (this.rule) {
      const { ruleKey, ...patch } = payload;
      this.update.emit({ id: this.rule.id, payload: patch });
    }
    // Re-enabled when parent closes us; if the parent leaves us mounted on
    // error, the consumer is expected to re-emit a save by clicking again.
    this.saving = false;
  }

  onCancel(): void { this.cancel.emit(); }

  // ── Validation ───────────────────────────────────────────────────────────

  private validate(): string | null {
    if (!this.form.name.trim())     return 'Name is required.';
    if (this.mode === 'create' && !this.form.ruleKey.trim()) return 'Rule key is required.';
    if (this.editorMode === 'visual') {
      const validRows = this.form.conditionRows.filter(r => r.field.trim() && r.value.toString().trim());
      if (validRows.length === 0) return 'Add at least one condition with a field and value.';
      if (!this.form.message.trim()) return 'Action message is required.';
      if (this.form.actionType === 'REJECT' && !this.form.rejectionCode.trim()) {
        return 'Rejection code is required when the action type is REJECT.';
      }
      if (this.actionTakesValue && !this.form.actionValue.trim()) {
        return `${this.form.actionType} requires a value (format: ${this.actionValueHint}).`;
      }
    } else {
      if (!this.form.drlOverride.trim()) return 'DRL is empty. Switch to visual mode or paste DRL here.';
    }
    return null;
  }

  // ── Mapping ──────────────────────────────────────────────────────────────

  private toPayload(): CreateRulePayload {
    const definition: RuleDefinition = this.buildDefinitionFromForm();
    return {
      ruleKey:     this.form.ruleKey.trim(),
      name:        this.form.name.trim(),
      description: this.form.description.trim() || undefined,
      category:    this.form.category,
      priority:    this.form.priority,
      enabled:     this.form.enabled,
      definition,
      drlOverride: this.editorMode === 'drl' ? this.form.drlOverride : undefined,
    };
  }

  private buildDefinitionFromForm(): RuleDefinition {
    const conditions: ConditionGroup = {
      operator: this.form.conditionsOperator,
      items: this.form.conditionRows
        .filter(r => r.field.trim())
        .map<Condition>(r => ({ field: r.field.trim(), operator: r.operator, value: this.coerce(r.value) })),
    };
    const action: { type: string; rejectionCode?: string; value?: unknown; message: string } = {
      type:    this.form.actionType,
      message: this.form.message.trim(),
    };
    if (this.form.actionType === 'REJECT' && this.form.rejectionCode.trim()) {
      action.rejectionCode = this.form.rejectionCode.trim();
    }
    if (this.actionTakesValue && this.form.actionValue.trim()) {
      action.value = this.form.actionValue.trim();
    }
    return {
      name:       this.form.name.trim(),
      description: this.form.description.trim() || undefined,
      category:   this.form.category,
      priority:   this.form.priority,
      enabled:    this.form.enabled,
      conditions,
      action,
    };
  }

  /** Best-effort coerce: numeric strings → number, "true"/"false" → boolean, otherwise string. */
  private coerce(raw: string): unknown {
    const t = (raw ?? '').toString().trim();
    if (t === '')       return '';
    if (t === 'true')   return true;
    if (t === 'false')  return false;
    if (!isNaN(Number(t)) && /^-?\d+(\.\d+)?$/.test(t)) return Number(t);
    return t;
  }

  // ── Initial state from inputs ────────────────────────────────────────────

  private blankForm(): EditorFormState {
    return {
      name: '', ruleKey: '', description: '',
      category: this.defaultCategory,
      priority: 50, enabled: true,
      conditionsOperator: 'AND',
      conditionRows: [{ field: '', operator: 'EQUALS', value: '' }],
      actionType: 'REJECT',
      rejectionCode: '',
      actionValue: '',
      message: '',
      drlOverride: '',
    };
  }

  /** Hint string for the Value input — drives whether to show the field at all. */
  get actionValueHint(): string {
    return this.actionTypes.find(a => a.id === this.form.actionType)?.valueHint ?? '';
  }

  get actionTakesValue(): boolean {
    return !!this.actionValueHint;
  }

  private formFromTemplate(t: RuleTemplate): EditorFormState {
    const def = t.definition;
    const cond = def.conditions ?? { operator: 'AND', items: [] };
    const rows = (cond.items?.length ? cond.items : [{ field: '', operator: 'EQUALS', value: '' }])
      .map(c => ({ field: c.field || '', operator: c.operator || 'EQUALS', value: this.stringify(c.value) }));
    return {
      // Auto-suggest a key from the template name (alphanum + dash, ≤150 chars).
      ruleKey: this.slugify(t.name),
      name: t.name,
      description: t.description ?? '',
      category: (t.category as RuleCategory) ?? this.defaultCategory,
      priority: t.priority ?? 50,
      enabled: true,
      conditionsOperator: (cond.operator === 'OR' ? 'OR' : 'AND'),
      conditionRows: rows,
      actionType:    def.action?.type ?? 'REJECT',
      rejectionCode: def.action?.rejectionCode ?? '',
      actionValue:   this.stringify((def.action as any)?.value),
      message:       def.action?.message ?? '',
      drlOverride: '',
    };
  }

  private formFromRule(rule: TenantRule): EditorFormState {
    const def = rule.definition ?? {};
    const cond = def.conditions ?? { operator: 'AND', items: [] };
    const rows = (cond.items?.length ? cond.items : [{ field: '', operator: 'EQUALS', value: '' }])
      .map(c => ({ field: c.field || '', operator: c.operator || 'EQUALS', value: this.stringify(c.value) }));
    return {
      ruleKey: rule.ruleKey,
      name: rule.name,
      description: rule.description ?? '',
      category: rule.category,
      priority: rule.priority,
      enabled: rule.enabled,
      conditionsOperator: (cond.operator === 'OR' ? 'OR' : 'AND'),
      conditionRows: rows,
      actionType:    def.action?.type ?? 'REJECT',
      rejectionCode: def.action?.rejectionCode ?? '',
      actionValue:   this.stringify((def.action as any)?.value),
      message:       def.action?.message ?? '',
      drlOverride:   rule.drlOverride ?? '',
    };
  }

  private stringify(v: unknown): string {
    if (v === null || v === undefined) return '';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
  }

  private slugify(name: string): string {
    return name.toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 80);
  }
}
