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
    enrollmentDate: new Date().toISOString().slice(0, 10),
  };

  constructor(
    private members: MembersService,
    private router: Router,
    private toast: ToastService,
  ) {}

  submit(): void {
    if (!this.form.firstName.trim() || !this.form.lastName.trim() || !this.form.dateOfBirth) {
      this.errorMessage = 'First name, last name and date of birth are required.';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    const payload = {
      firstName:      this.form.firstName.trim(),
      lastName:       this.form.lastName.trim(),
      dateOfBirth:    this.form.dateOfBirth,
      gender:         this.form.gender.trim()     || undefined,
      nationalId:     this.form.nationalId.trim() || undefined,
      email:          this.form.email.trim()      || undefined,
      phone:          this.form.phone.trim()      || undefined,
      address:        this.form.address.trim()    || undefined,
      groupId:        this.form.groupId           || undefined,
      schemeId:       this.form.schemeId          || undefined,
      enrollmentDate: this.form.enrollmentDate    || undefined,
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
