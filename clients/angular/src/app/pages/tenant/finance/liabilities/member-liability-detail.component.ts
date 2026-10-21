import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  MemberLiabilityDetail,
  MemberLiabilityService,
} from '../../../../core/services/member-liability.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

/**
 * Detail view for a single V078 {@code member_cost_share_liability}
 * row (Phase 4 copayments). Shows the seven cost-share buckets plus a
 * sub-table of every settlement applied against the liability.
 */
@Component({
  selector: 'app-member-liability-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './member-liability-detail.component.html',
  styleUrl: './member-liability-detail.component.scss',
})
export class MemberLiabilityDetailComponent implements OnInit {
  detail: MemberLiabilityDetail | null = null;
  loading = false;

  constructor(
    private service: MemberLiabilityService,
    private route: ActivatedRoute,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.toast.error('Missing liability id');
      return;
    }
    this.loading = true;
    this.service.getById(id).subscribe({
      next: (d) => { this.detail = d; this.loading = false; },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Failed to load liability'));
        this.loading = false;
      },
    });
  }
}
