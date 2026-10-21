import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../../core/services/api.service';

/**
 * Phase 17 §C.2 — public unsubscribe confirmation page.
 *
 * <p>Landed on from the footer link in scheduled report delivery emails
 * (`/public/unsubscribe/{token}`). No JWT — the gateway allowlist bypasses
 * auth for this path; the URL-embedded UUID token is the sole credential.
 *
 * <p>Flow: token is validated as a UUID shape on init; user clicks
 * "Unsubscribe me" (optionally supplying a reason) → POST to the tenancy
 * unsubscribe endpoint → success / already-unsubscribed / error state.
 *
 * <p>The backend deliberately returns `success: false` for both unknown and
 * expired tokens (no enumeration signal); we surface a single "couldn't
 * process" message rather than distinguishing the failure modes.
 */
interface UnsubscribeResponse {
  success: boolean;
  email: string | null;
}

type PageState = 'ready' | 'submitting' | 'success' | 'not_found' | 'invalid_token' | 'error';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

@Component({
  selector: 'app-unsubscribe-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './unsubscribe-page.component.html',
  styleUrl: './unsubscribe-page.component.scss',
})
export class UnsubscribePageComponent implements OnInit {
  token: string | null = null;
  reason = '';
  state: PageState = 'ready';
  deactivatedEmail: string | null = null;
  errorDetail: string | null = null;

  constructor(private route: ActivatedRoute, private api: ApiService) {}

  ngOnInit(): void {
    const raw = this.route.snapshot.paramMap.get('token');
    this.token = raw;
    if (!raw || !UUID_RE.test(raw)) {
      this.state = 'invalid_token';
    }
  }

  confirm(): void {
    if (!this.token || this.state === 'submitting'
        || this.state === 'success' || this.state === 'invalid_token') return;
    this.state = 'submitting';
    this.errorDetail = null;
    const body = this.reason.trim() ? { reason: this.reason.trim() } : {};
    this.api
      .post<UnsubscribeResponse>(
        `/report-schedule-recipients/unsubscribe/${this.token}`, body)
      .subscribe({
        next: (res) => {
          if (res.success) {
            this.deactivatedEmail = res.email;
            this.state = 'success';
          } else {
            this.state = 'not_found';
          }
        },
        error: (err) => {
          this.errorDetail = err?.error?.detail || 'The request could not be processed.';
          this.state = 'error';
        },
      });
  }
}
