import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MembersService } from '../../../../core/services/members.service';
import { EntityPickerComponent } from '../../../../shared/components/entity-picker/entity-picker.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

interface AddMemberForm {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  nationalId: string;
  email: string;
  phone: string;
  address: string;
  groupId: string;
  schemeId: string;
  enrollmentDate: string;
}

@Component({
  selector: 'app-member-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, EntityPickerComponent],
  templateUrl: './member-form.component.html',
  styleUrl: './member-form.component.scss',
})
export class MemberFormComponent {
  saving = false;
  errorMessage: string | null = null;

  form: AddMemberForm = {
    firstName: '', lastName: '', dateOfBirth: '',
    gender: '', nationalId: '', email: '', phone: '', address: '',
    groupId: '', schemeId: '',
    // Default to the 1st of the current month — enrollment dates are
    // always month-anchored.
    enrollmentDate: new Date().toISOString().slice(0, 8) + '01',
  };

  constructor(
    private members: MembersService,
    private router: Router,
    private toast: ToastService,
  ) {}

  /** Normalise an ISO YYYY-MM-DD date to the 1st of its month. */
  private firstOfMonth(iso: string): string {
    if (!iso || iso.length < 7) return iso;
    return iso.slice(0, 8) + '01';
  }

  /** Bound to (ngModelChange) on the enrollment-date input so any operator
   *  pick snaps to the 1st of the chosen month before it sits in form state. */
  onEnrollmentDateChange(): void {
    this.form.enrollmentDate = this.firstOfMonth(this.form.enrollmentDate);
  }

  submit(): void {
    const missing: string[] = [];
    if (!this.form.firstName.trim())  missing.push('first name');
    if (!this.form.lastName.trim())   missing.push('last name');
    if (!this.form.dateOfBirth)       missing.push('date of birth');
    if (!this.form.gender.trim())     missing.push('gender');
    if (!this.form.nationalId.trim()) missing.push('national ID');
    if (!this.form.email.trim())      missing.push('email');
    if (!this.form.schemeId)          missing.push('scheme');
    if (missing.length > 0) {
      this.errorMessage = `Required field${missing.length > 1 ? 's' : ''} missing: ${missing.join(', ')}.`;
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    const payload = {
      firstName:      this.form.firstName.trim(),
      lastName:       this.form.lastName.trim(),
      dateOfBirth:    this.form.dateOfBirth,
      gender:         this.form.gender.trim(),
      nationalId:     this.form.nationalId.trim(),
      email:          this.form.email.trim(),
      phone:          this.form.phone.trim()      || undefined,
      address:        this.form.address.trim()    || undefined,
      groupId:        this.form.groupId           || undefined,
      schemeId:       this.form.schemeId,
      // Always the 1st of the chosen month. Back-dating is allowed — the
      // contributions side will post the arrears adjustment when the
      // enrolment date is in a past period (deferred to a follow-up).
      enrollmentDate: this.firstOfMonth(this.form.enrollmentDate),
    };
    this.members.enroll(payload).subscribe({
      next: (saved) => {
        this.saving = false;
        this.toast.success(`Member ${saved.firstName} ${saved.lastName} enrolled`);
        this.router.navigate(['/tenant/members', saved.id]);
      },
      error: (err) => {
        this.saving = false;
        const msg = err?.error?.detail || err?.error?.title || 'Enrolment failed';
        this.errorMessage = msg;
        this.toast.error(msg);
      },
    });
  }
}
