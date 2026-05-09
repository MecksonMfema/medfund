import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Claim, ClaimsService } from '../../../../core/services/claims.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-verification-codes',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, HumanizePipe],
  templateUrl: './verification-codes.component.html',
  styleUrl: './verification-codes.component.scss',
})
export class VerificationCodesComponent {
  // Two flows on the same page:
  //  - Lookup by claim number → reveals the active verification code
  //    so a clerk reading from a paper form can confirm to the member.
  //  - Verify by claim number + code → flips status to VERIFIED.

  claimNumber = '';
  verificationCode = '';

  found: Claim | null = null;
  loading = false;
  verifying = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(private claims: ClaimsService, private router: Router) {}

  lookup(): void {
    this.errorMessage = null;
    this.successMessage = null;
    this.found = null;
    if (!this.claimNumber.trim()) {
      this.errorMessage = 'Enter a claim number';
      return;
    }
    this.loading = true;
    // The backend offers find-by-id and find-by-status but not by claim_number;
    // for the front-desk workflow the clerk usually has the id off the
    // submission receipt. We treat the input as the claim id directly when
    // it parses as a UUID, else surface a friendly error.
    const looksLikeUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      this.claimNumber.trim(),
    );
    if (!looksLikeUuid) {
      this.loading = false;
      this.errorMessage = 'Paste the claim id from the submission receipt (UUID format).';
      return;
    }
    this.claims.getById(this.claimNumber.trim()).subscribe({
      next: (claim) => { this.found = claim; this.loading = false; },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.status === 404 ? 'Claim not found' : (err?.error?.detail || 'Lookup failed');
      },
    });
  }

  verify(): void {
    if (!this.found) return;
    if (!this.verificationCode.trim()) {
      this.errorMessage = 'Enter the verification code';
      return;
    }
    this.verifying = true;
    this.errorMessage = null;
    this.claims.verify(this.found.id, this.verificationCode.trim()).subscribe({
      next: (claim) => {
        this.found = claim;
        this.verifying = false;
        this.successMessage = `Claim ${claim.claimNumber} verified.`;
      },
      error: (err) => {
        this.verifying = false;
        this.errorMessage = err?.error?.detail || 'Verification failed — code may be wrong or expired';
      },
    });
  }

  openClaim(): void {
    if (this.found) this.router.navigate(['/tenant/claims', this.found.id]);
  }

  reset(): void {
    this.claimNumber = '';
    this.verificationCode = '';
    this.found = null;
    this.errorMessage = null;
    this.successMessage = null;
  }
}
