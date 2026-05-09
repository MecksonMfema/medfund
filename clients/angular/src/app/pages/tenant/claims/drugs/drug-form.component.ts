import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Drug, DrugsService, UpsertDrugPayload } from '../../../../core/services/drugs.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-drug-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './drug-form.component.html',
  styleUrl: './drug-form.component.scss',
})
export class DrugFormComponent implements OnInit {
  drugId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  readonly drugTypes = ['ACUTE', 'CHRONIC', 'OFF_LIMIT'] as const;
  readonly units = ['unit', 'ml', 'g', 'mg', 'tablet', 'capsule'] as const;

  form: UpsertDrugPayload = {
    drugName: '',
    drugType: 'ACUTE',
    unitOfMeasurement: 'unit',
    tariffCode: '',
    wholesaleCostZwl: '',
    wholesaleCostUsd: '',
    paymentPercentage: '100',
    doNotPay: false,
    isActive: true,
  };

  constructor(
    private drugs: DrugsService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.drugId = this.route.snapshot.paramMap.get('id');
    if (!this.drugId) return;
    this.loading = true;
    this.drugs.findById(this.drugId).subscribe({
      next: (d: Drug) => {
        this.form = {
          drugName: d.drugName,
          drugType: d.drugType,
          unitOfMeasurement: d.unitOfMeasurement,
          tariffCode: d.tariffCode ?? '',
          wholesaleCostZwl: d.wholesaleCostZwl ?? '',
          wholesaleCostUsd: d.wholesaleCostUsd ?? '',
          paymentPercentage: d.paymentPercentage,
          doNotPay: d.doNotPay,
          isActive: d.isActive,
        };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load drug';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.drugName?.trim()) {
      this.errorMessage = 'Drug name is required';
      return;
    }
    const payload: UpsertDrugPayload = {
      drugName: this.form.drugName.trim(),
      drugType: this.form.drugType,
      unitOfMeasurement: this.form.unitOfMeasurement,
      tariffCode: this.form.tariffCode?.trim() || undefined,
      wholesaleCostZwl: this.form.wholesaleCostZwl || undefined,
      wholesaleCostUsd: this.form.wholesaleCostUsd || undefined,
      paymentPercentage: this.form.paymentPercentage || '100',
      doNotPay: this.form.doNotPay,
      isActive: this.form.isActive,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.drugId
      ? this.drugs.update(this.drugId, payload)
      : this.drugs.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/claims/drugs']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
