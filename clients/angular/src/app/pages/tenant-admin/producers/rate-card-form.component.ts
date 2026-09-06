import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CreateRateCardPayload,
  InsuranceLine,
  ProducerService,
  RateCard,
  UpdateRateCardPayload,
} from '../../../core/services/producer.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { endOfMonth, firstOfMonth } from '../../../shared/utils/date-snap';

interface RateCardForm {
  name: string;
  insuranceLine: InsuranceLine;
  producerTier: string;
  baseRatePct: number | null;
  clawbackWindowDays: number | null;
  effectiveFrom: string;
  effectiveTo: string;
  active: boolean;
}

@Component({
  selector: 'app-rate-card-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './rate-card-form.component.html',
  styleUrl: './rate-card-form.component.scss',
})
export class RateCardFormComponent implements OnInit {
  rateCardId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  readonly insuranceLines: InsuranceLine[] = [
    'HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY',
  ];

  readonly insuranceLineOptions: SelectOption[] =
    this.insuranceLines.map(l => ({ value: l, label: l }));

  form: RateCardForm = {
    name: '',
    insuranceLine: 'HEALTH',
    producerTier: '',
    baseRatePct: null,
    clawbackWindowDays: null,
    effectiveFrom: firstOfMonth(new Date().toISOString().slice(0, 10)),
    effectiveTo: '',
    active: true,
  };

  private originalForm: RateCardForm | null = null;

  get isEdit(): boolean { return !!this.rateCardId; }

  get isDirty(): boolean {
    if (!this.originalForm) return true;
    const f = this.form, o = this.originalForm;
    return (
      f.name               !== o.name ||
      f.insuranceLine      !== o.insuranceLine ||
      f.producerTier       !== o.producerTier ||
      f.baseRatePct        !== o.baseRatePct ||
      f.clawbackWindowDays !== o.clawbackWindowDays ||
      f.effectiveFrom      !== o.effectiveFrom ||
      f.effectiveTo        !== o.effectiveTo ||
      f.active             !== o.active
    );
  }

  constructor(
    private svc: ProducerService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.rateCardId = this.route.snapshot.paramMap.get('id');
    if (!this.rateCardId) return;

    this.loading = true;
    this.svc.getRateCard(this.rateCardId).subscribe({
      next: (rc: RateCard) => {
        this.form = {
          name: rc.name,
          insuranceLine: rc.insuranceLine,
          producerTier: rc.producerTier ?? '',
          baseRatePct: rc.baseRatePct,
          clawbackWindowDays: rc.clawbackWindowDays ?? null,
          effectiveFrom: rc.effectiveFrom,
          effectiveTo: rc.effectiveTo ?? '',
          active: rc.active,
        };
        this.originalForm = { ...this.form };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load rate card';
        this.loading = false;
      },
    });
  }

  onEffectiveFromBlur(): void {
    this.form.effectiveFrom = firstOfMonth(this.form.effectiveFrom);
  }

  onEffectiveToBlur(): void {
    if (this.form.effectiveTo) {
      this.form.effectiveTo = endOfMonth(this.form.effectiveTo);
    }
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (this.form.baseRatePct == null || this.form.baseRatePct < 0 || this.form.baseRatePct > 100) {
      this.errorMessage = 'Base rate must be between 0 and 100';
      return;
    }
    if (!this.form.effectiveFrom) {
      this.errorMessage = 'Effective-from date is required';
      return;
    }

    const effectiveFrom = firstOfMonth(this.form.effectiveFrom);
    const effectiveTo = this.form.effectiveTo ? endOfMonth(this.form.effectiveTo) : null;

    const base: CreateRateCardPayload = {
      name: this.form.name.trim(),
      insuranceLine: this.form.insuranceLine,
      producerTier: this.form.producerTier.trim() || null,
      baseRatePct: this.form.baseRatePct,
      clawbackWindowDays: this.form.clawbackWindowDays ?? null,
      effectiveFrom,
      effectiveTo,
    };

    this.saving = true;
    this.errorMessage = null;

    const stream = this.rateCardId
      ? this.svc.updateRateCard(this.rateCardId, { ...base, active: this.form.active } as UpdateRateCardPayload)
      : this.svc.createRateCard(base);

    stream.subscribe({
      next: () => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Rate card updated' : 'Rate card created');
        this.router.navigate(['/tenant/admin/producers/rate-cards']);
      },
      error: (err) => {
        this.saving = false;
        const detail = err?.error?.detail || err?.error?.title || 'Save failed';
        this.errorMessage = detail;
        this.toast.error(detail);
      },
    });
  }
}
