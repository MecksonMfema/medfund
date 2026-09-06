import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CreateIfrs17PortfolioPayload,
  Ifrs17Portfolio,
  Ifrs17PortfolioService,
  InsuranceLine,
} from '../../../core/services/ifrs17-portfolio.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../shared/components/select/select.component';
import { ToastService } from '../../../shared/components/toast/toast.service';

interface PortfolioForm {
  name: string;
  description: string;
  insuranceLine: InsuranceLine | '';
}

@Component({
  selector: 'app-portfolio-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './portfolio-form.component.html',
  styleUrl: './portfolio-form.component.scss',
})
export class PortfolioFormComponent implements OnInit {
  portfolioId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  readonly insuranceLineOptions: SelectOption[] = [
    { value: '',           label: '(MISC catchall)' },
    { value: 'HEALTH',     label: 'HEALTH' },
    { value: 'LIFE',       label: 'LIFE' },
    { value: 'FUNERAL',    label: 'FUNERAL' },
    { value: 'GROUP',      label: 'GROUP' },
    { value: 'TRAVEL',     label: 'TRAVEL' },
    { value: 'DISABILITY', label: 'DISABILITY' },
    { value: 'VEHICLE',    label: 'VEHICLE' },
    { value: 'PROPERTY',   label: 'PROPERTY' },
  ];

  form: PortfolioForm = {
    name: '',
    description: '',
    insuranceLine: '',
  };

  private originalForm: PortfolioForm | null = null;

  get isEdit(): boolean { return !!this.portfolioId; }

  get isDirty(): boolean {
    if (!this.originalForm) return true;
    const f = this.form, o = this.originalForm;
    return (
      f.name          !== o.name ||
      f.description   !== o.description ||
      f.insuranceLine !== o.insuranceLine
    );
  }

  constructor(
    private svc: Ifrs17PortfolioService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.portfolioId = this.route.snapshot.paramMap.get('id');
    if (!this.portfolioId) return;

    this.loading = true;
    this.svc.getById(this.portfolioId).subscribe({
      next: (p: Ifrs17Portfolio) => {
        this.form = {
          name: p.name,
          description: p.description ?? '',
          insuranceLine: p.insuranceLine ?? '',
        };
        this.originalForm = { ...this.form };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load portfolio';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.name.trim()) {
      this.errorMessage = 'Name is required';
      return;
    }

    const payload: CreateIfrs17PortfolioPayload = {
      name: this.form.name.trim(),
      description: this.form.description.trim() || null,
      insuranceLine: this.form.insuranceLine || null,
    };

    this.saving = true;
    this.errorMessage = null;

    const stream = this.portfolioId
      ? this.svc.update(this.portfolioId, payload)
      : this.svc.create(payload);

    stream.subscribe({
      next: () => {
        this.saving = false;
        this.toast.success(this.isEdit ? 'Portfolio updated' : 'Portfolio created');
        this.router.navigate(['/tenant/admin/underwriting/portfolios']);
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
