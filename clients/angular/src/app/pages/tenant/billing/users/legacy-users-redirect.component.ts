import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-legacy-users-redirect',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './legacy-users-redirect.component.html',
  styleUrl: './legacy-users-redirect.component.scss',
})
export class LegacyUsersRedirectComponent {}
