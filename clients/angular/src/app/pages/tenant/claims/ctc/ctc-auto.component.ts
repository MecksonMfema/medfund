import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-ctc-auto',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './ctc-auto.component.html',
  styleUrl: './ctc-auto.component.scss',
})
export class CtcAutoComponent {}
