import { of, throwError } from 'rxjs';
import { AiModelsComponent } from './ai-models.component';
import {
  AiActiveModel,
  AiPredictionsService,
  AiPromoteResponse,
} from '../../../../core/services/ai-predictions.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Phase 5 admin surface — verifies 16-row rendering, sort by
 * (model_type, line), promote modal flow, SCHEMA_MISMATCH badge,
 * and the `ai:models:promote` permission gate.
 */
describe('AiModelsComponent', () => {
  let api: jasmine.SpyObj<AiPredictionsService>;
  let permissions: jasmine.SpyObj<PermissionService>;
  let toast: jasmine.SpyObj<ToastService>;
  let component: AiModelsComponent;

  const buildRow = (overrides: Partial<AiActiveModel>): AiActiveModel => ({
    model_type: 'fraud',
    line: 'HEALTH',
    active_version: null,
    model_version: 'fraud-isolation-forest-v1-canonical',
    is_fallback: true,
    trained_at: null,
    train_samples: 0,
    metrics: {},
    schema_status: 'OK',
    schema_status_detail: null,
    ...overrides,
  });

  const LINES = ['HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'] as const;
  const buildSixteenRows = (): AiActiveModel[] => {
    const rows: AiActiveModel[] = [];
    for (const model_type of ['fraud','pricing'] as const) {
      for (const line of LINES) {
        rows.push(buildRow({
          model_type,
          line,
          model_version: model_type === 'fraud' ? 'fraud-isolation-forest-v1-canonical' : 'rule-v1',
        }));
      }
    }
    // Randomize order so the sort assertion is meaningful.
    return rows.reverse();
  };

  beforeEach(() => {
    api = jasmine.createSpyObj<AiPredictionsService>('AiPredictionsService', [
      'listActiveModels', 'promoteModel',
    ]);
    api.listActiveModels.and.returnValue(of(buildSixteenRows()));

    permissions = jasmine.createSpyObj<PermissionService>('PermissionService', ['has']);
    permissions.has.and.callFake((_p) => true);

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    component = new AiModelsComponent(api as any, permissions as any, toast as any);
  });

  // ── Rendering / sort ─────────────────────────────────────────────────

  it('loads 16 rows on init and sorts by (model_type, line)', () => {
    component.ngOnInit();

    expect(api.listActiveModels).toHaveBeenCalledTimes(1);
    expect(component.models.length).toBe(16);

    // First 8 are fraud, sorted alphabetically by line.
    const fraudLines = component.models
      .filter(m => m.model_type === 'fraud').map(m => m.line);
    expect(fraudLines).toEqual([...fraudLines].sort());

    // fraud rows precede pricing rows.
    expect(component.models[0].model_type).toBe('fraud');
    expect(component.models[8].model_type).toBe('pricing');
  });

  it('surfaces load errors', () => {
    api.listActiveModels.and.returnValue(throwError(() => new Error('boom')));
    component.ngOnInit();
    expect(component.loadError).toContain('boom');
    expect(component.loading).toBeFalse();
  });

  it('primaryMetric renders auc for fraud and dash when absent', () => {
    const trained = buildRow({
      model_type: 'fraud', line: 'HEALTH',
      active_version: 'v2', is_fallback: false,
      model_version: 'fraud-health-v2',
      metrics: { auc: 0.847 },
    });
    expect(component.primaryMetric(trained)).toBe('0.847');

    const noMetrics = buildRow({ model_type: 'fraud' });
    expect(component.primaryMetric(noMetrics)).toBe('—');
  });

  it('statusLabel + statusBadgeClass reflect fallback / trained / mismatch', () => {
    const trained = buildRow({ is_fallback: false });
    expect(component.statusLabel(trained)).toBe('Trained');
    expect(component.statusBadgeClass(trained)).toBe('badge-green');

    const fallback = buildRow({ is_fallback: true });
    expect(component.statusLabel(fallback)).toBe('Canonical fallback');
    expect(component.statusBadgeClass(fallback)).toBe('badge-grey');

    const mismatch = buildRow({
      is_fallback: false, schema_status: 'SCHEMA_MISMATCH',
      schema_status_detail: 'artifact_schema="v99" runtime_schema="v1"',
    });
    expect(component.statusLabel(mismatch)).toBe('SCHEMA_MISMATCH');
    expect(component.statusBadgeClass(mismatch)).toBe('badge-red');
  });

  // ── Permission gate ────────────────────────────────────────────────

  it('canPromote defers to PermissionService', () => {
    permissions.has.and.callFake(p => p === 'ai:models:promote');
    expect(component.canPromote()).toBeTrue();

    permissions.has.and.callFake(_ => false);
    expect(component.canPromote()).toBeFalse();
  });

  // ── Promote modal flow ────────────────────────────────────────────

  it('openPromote populates the modal from the row', () => {
    const row = buildRow({
      model_type: 'fraud', line: 'HEALTH',
      active_version: 'v1', is_fallback: false,
      model_version: 'fraud-health-v1',
    });
    component.openPromote(row);
    expect(component.showPromote).toBeTrue();
    expect(component.promoteRow).toBe(row);
    expect(component.promoteVersionInput).toBe('v1');
  });

  it('confirmPromote calls the API and toasts on success', () => {
    const row = buildRow({
      model_type: 'fraud', line: 'HEALTH',
      active_version: 'v1', is_fallback: false,
    });
    api.promoteModel.and.returnValue(of<AiPromoteResponse>({
      model_type: 'fraud', line: 'HEALTH',
      before: 'v1', after: 'v2', audit_event_id: 'evt-1',
    }));

    component.ngOnInit();
    component.openPromote(row);
    component.promoteVersionInput = 'v2';
    component.confirmPromote();

    expect(api.promoteModel).toHaveBeenCalledWith('fraud', 'HEALTH', 'v2');
    expect(toast.success).toHaveBeenCalled();
    expect(component.showPromote).toBeFalse();
    // Reload should have been triggered.
    expect(api.listActiveModels).toHaveBeenCalledTimes(2); // ngOnInit + reload
  });

  it('confirmPromote surfaces the server error when the API fails', () => {
    const row = buildRow({ model_type: 'fraud', line: 'HEALTH' });
    api.promoteModel.and.returnValue(throwError(() => ({
      error: { detail: 'artifact not found' },
    })));
    component.openPromote(row);
    component.promoteVersionInput = 'v-nope';
    component.confirmPromote();

    expect(component.promoteError).toBe('artifact not found');
    expect(component.showPromote).toBeTrue();
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('confirmPromote refuses to submit an empty version', () => {
    const row = buildRow({ model_type: 'fraud', line: 'HEALTH' });
    component.openPromote(row);
    component.promoteVersionInput = '   ';
    component.confirmPromote();
    expect(component.promoteError).toContain('required');
    expect(api.promoteModel).not.toHaveBeenCalled();
  });

  it('cancelPromote clears modal state', () => {
    const row = buildRow({ model_type: 'fraud', line: 'HEALTH' });
    component.openPromote(row);
    component.cancelPromote();

    expect(component.showPromote).toBeFalse();
    expect(component.promoteRow).toBeNull();
    expect(component.promoteVersionInput).toBe('');
    expect(component.promoteError).toBe('');
  });
});
