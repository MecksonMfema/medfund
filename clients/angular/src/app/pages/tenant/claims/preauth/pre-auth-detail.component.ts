import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  PreAuthorization,
  PreAuthService,
} from '../../../../core/services/pre-auth.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-pre-auth-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './pre-auth-detail.component.html',
  styleUrl: './pre-auth-detail.component.scss',
})
export class PreAuthDetailComponent implements OnInit {
  preAuth: PreAuthorization | null = null;
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // Decision form state
  approvedAmount = '';
  expiryDate = '';
  rejectReason = '';

  constructor(
    private service: PreAuthService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.router.navigate(['/tenant/claims/preauth']); return; }
    this.loading = true;
    this.service.findById(id).subscribe({
      next: (p) => {
        this.preAuth = p;
        this.approvedAmount = p.requestedAmount; // sensible default
        // Default expiry: +90 days from today (typical clinical pre-auth window).
        const d = new Date();
        d.setDate(d.getDate() + 90);
        this.expiryDate = d.toISOString().slice(0, 10);
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load pre-authorization';
        this.loading = false;
      },
    });
  }

  approve(): void {
    if (!this.preAuth) return;
    if (!this.approvedAmount || !this.expiryDate) {
      this.errorMessage = 'Approved amount and expiry date are required';
      return;
    }
    if (!confirm(`Approve ${this.preAuth.authNumber} for ${this.approvedAmount} ${this.preAuth.currencyCode} until ${this.expiryDate}?`)) return;
    this.busy = true;
    this.errorMessage = null;
    this.service.approve(this.preAuth.id, this.approvedAmount, this.expiryDate).subscribe({
      next: (updated) => {
        this.preAuth = updated;
        this.busy = false;
        this.successMessage = `Pre-auth ${updated.authNumber} approved.`;
      },
      error: (err) => {
        this.busy = false;
        this.errorMessage = err?.error?.detail || 'Approval failed';
      },
    });
  }

  reject(): void {
    if (!this.preAuth) return;
    if (!this.rejectReason.trim()) {
      this.errorMessage = 'Rejection reason is required';
      return;
    }
    if (!confirm(`Reject ${this.preAuth.authNumber}?`)) return;
    this.busy = true;
    this.errorMessage = null;
    this.service.reject(this.preAuth.id, this.rejectReason.trim()).subscribe({
      next: (updated) => {
        this.preAuth = updated;
        this.busy = false;
        this.successMessage = `Pre-auth ${updated.authNumber} rejected.`;
      },
      error: (err) => {
        this.busy = false;
        this.errorMessage = err?.error?.detail || 'Rejection failed';
      },
    });
  }

  printLetter(): void {
    window.print();
  }

  canDecide(): boolean {
    return this.preAuth?.status === 'pending';
  }
}
