import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CreateProducerPayload,
  Producer,
  ProducerService,
  UpdateProducerPayload,
} from '../../../core/services/producer.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../core/services/currency.service';
import { TenantService } from '../../../core/services/tenant.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import { ToastService } from '../../../shared/components/toast/toast.service';

interface ProducerForm {
  producerCode: string;
  name: string;
  homeCurrency: string;
  jurisdictionCode: string;
  contactEmail: string;
  contactPhone: string;
  whtPctOverride: number | null;
  parentProducerId: string | null;
  parentProducerLabel: string | null;
  bankingDetailsJson: string;
  active: boolean;
}

@Component({
  selector: 'app-producer-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './producer-form.component.html',
  styleUrl: './producer-form.component.scss',
})
export class ProducerFormComponent implements OnInit {
  producerId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  allowedCurrencies: TenantCurrencyConfig[] = [];

  form: ProducerForm = {
    producerCode: '',
    name: '',
    homeCurrency: '',
    jurisdictionCode: '',
    contactEmail: '',
    contactPhone: '',
    whtPctOverride: null,
    parentProducerId: null,
    parentProducerLabel: null,
    bankingDetailsJson: '',
    active: true,
  };

  private originalForm: ProducerForm | null = null;

  parentSearchQuery = '';
  parentMatches: Producer[] = [];
  parentSearching = false;
  private parentSearchTimer: ReturnType<typeof setTimeout> | null = null;

  get isEdit(): boolean { return !!this.producerId; }

  get isDirty(): boolean {
    if (!this.originalForm) return true;
    const f = this.form, o = this.originalForm;
    return (
      f.producerCode        !== o.producerCode ||
      f.name                !== o.name ||
      f.homeCurrency        !== o.homeCurrency ||
      f.jurisdictionCode    !== o.jurisdictionCode ||
      f.contactEmail        !== o.contactEmail ||
      f.contactPhone        !== o.contactPhone ||
      f.whtPctOverride      !== o.whtPctOverride ||
      f.parentProducerId    !== o.parentProducerId ||
      f.bankingDetailsJson  !== o.bankingDetailsJson ||
      f.active              !== o.active
    );
  }

  get currencySelectOptions(): SelectOption[] {
    return this.allowedCurrencies.map(c => ({
      value: c.currencyCode,
      label: c.currencyCode,
      description: c.isDefault ? 'Default' : undefined,
    }));
  }

  constructor(
    private svc: ProducerService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: (configs) => {
          this.allowedCurrencies = configs.filter(c => c.isActive);
          const def = configs.find(c => c.isDefault);
          if (!this.form.homeCurrency && def) this.form.homeCurrency = def.currencyCode;
        },
      });
    }

    this.producerId = this.route.snapshot.paramMap.get('id');
    if (!this.producerId) return;

    this.loading = true;
    this.svc.getProducer(this.producerId).subscribe({
      next: (p: Producer) => {
        this.form = {
          producerCode: p.producerCode,
          name: p.name,
          homeCurrency: p.homeCurrency,
          jurisdictionCode: p.jurisdictionCode ?? '',
          contactEmail: p.contactEmail ?? '',
          contactPhone: p.contactPhone ?? '',
          whtPctOverride: p.whtPctOverride ?? null,
          parentProducerId: p.parentProducerId ?? null,
          parentProducerLabel: null,
          bankingDetailsJson: p.bankingDetailsJson ?? '',
          active: p.active,
        };
        if (p.parentProducerId) {
          this.svc.getProducer(p.parentProducerId).subscribe({
            next: parent => {
              this.form.parentProducerLabel = `${parent.producerCode} - ${parent.name}`;
              if (this.originalForm) this.originalForm.parentProducerLabel = this.form.parentProducerLabel;
            },
          });
        }
        this.originalForm = { ...this.form };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load producer';
        this.loading = false;
      },
    });
  }

  onParentSearchChange(): void {
    if (this.parentSearchTimer) clearTimeout(this.parentSearchTimer);
    const q = this.parentSearchQuery.trim();
    if (!q) { this.parentMatches = []; return; }
    this.parentSearching = true;
    this.parentSearchTimer = setTimeout(() => {
      this.svc.searchProducers(q, 10).subscribe({
        next: rows => {
          this.parentMatches = this.producerId
            ? rows.filter(r => r.id !== this.producerId)
            : rows;
          this.parentSearching = false;
        },
        error: () => { this.parentMatches = []; this.parentSearching = false; },
      });
    }, 300);
  }

  pickParent(p: Producer): void {
    this.form.parentProducerId = p.id;
    this.form.parentProducerLabel = `${p.producerCode} - ${p.name}`;
    this.parentSearchQuery = '';
    this.parentMatches = [];
  }

  clearParent(): void {
    this.form.parentProducerId = null;
    this.form.parentProducerLabel = null;
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }
    if (!this.form.homeCurrency.trim() || this.form.homeCurrency.length !== 3) {
      this.errorMessage = 'Home currency must be a 3-letter ISO code';
      return;
    }
    if (!this.producerId && !this.form.producerCode.trim()) {
      this.errorMessage = 'Producer code is required';
      return;
    }

    const base = {
      name: this.form.name.trim(),
      contactEmail: this.form.contactEmail.trim() || undefined,
      contactPhone: this.form.contactPhone.trim() || undefined,
      jurisdictionCode: this.form.jurisdictionCode.trim() || undefined,
      homeCurrency: this.form.homeCurrency.trim().toUpperCase(),
      parentProducerId: this.form.parentProducerId || null,
      whtPctOverride: this.form.whtPctOverride ?? null,
      bankingDetailsJson: this.form.bankingDetailsJson.trim() || null,
    };

    this.saving = true;
    this.errorMessage = null;

    const stream = this.producerId
      ? this.svc.updateProducer(this.producerId,
          { ...base, active: this.form.active } as UpdateProducerPayload)
      : this.svc.createProducer({
          ...base,
          producerCode: this.form.producerCode.trim(),
        } as CreateProducerPayload);

    stream.subscribe({
      next: () => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Producer updated' : 'Producer created');
        this.router.navigate(['/tenant/admin/producers/list']);
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
