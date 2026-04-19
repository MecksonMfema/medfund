import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { map } from 'rxjs/operators';
import { DataTableComponent, TableAction } from '../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { AdminService } from '../../../core/services/admin.service';

interface DiffRow {
  field: string;
  before: any;
  after: any;
}

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent],
  templateUrl: './audit.component.html',
  styleUrl: './audit.component.scss',
})
export class AuditComponent implements OnInit {
  events: any[] = [];
  loading = false;
  totalCount = 0;
  totalPages = 1;
  currentPage = 1;
  readonly pageSize = 15;

  searchTerm = '';
  entityFilter = '';
  actionFilter = '';
  dateFrom = '';
  dateTo = '';

  selectedEvent: any = null;
  showDiff = false;

  // Display columns use _formatted variants (UUIDs hidden as '—')
  columns = [
    { key: 'entityType',            label: 'Entity' },
    { key: '_entityDisplayFormatted', label: 'Name' },
    { key: 'action',                label: 'Action', type: 'status' },
    { key: '_actorDisplayFormatted', label: 'Actor' },
    { key: 'timestamp',             label: 'Timestamp', type: 'date' },
  ];

  tableActions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      visible: (row) => ['CREATE', 'UPDATE', 'DELETE'].includes(row.action),
      handler: (row) => this.openDiff(row),
    },
  ];

  constructor(private adminService: AdminService) {}

  ngOnInit(): void {
    this.loadPage(1);
  }

  loadPage(page: number): void {
    this.loading = true;
    const params: Record<string, string> = {
      page: String(page),
      pageSize: String(this.pageSize),
    };
    if (this.searchTerm)   params['q']          = this.searchTerm;
    if (this.entityFilter) params['entityType'] = this.entityFilter;
    if (this.actionFilter) params['action']     = this.actionFilter;
    if (this.dateFrom)     params['startDate']  = this.dateFrom;
    if (this.dateTo)       params['endDate']    = this.dateTo;

    this.adminService.getAuditEvents(params).subscribe({
      next: (data) => {
        this.totalCount  = data.total || 0;
        this.totalPages  = Math.max(Math.ceil(this.totalCount / this.pageSize), 1);
        this.currentPage = page;
        this.events = (data.events || []).map((e: any) => {
          const entityDisplay = e.entityName || e.entityId;
          const actorDisplay  = e.actorEmail  || e.actorId;
          return {
            ...e,
            // Raw: used in CSV export (UUIDs preserved)
            _entityDisplay: entityDisplay,
            _actorDisplay:  actorDisplay,
            // Formatted: shown in UI (UUIDs replaced with '—')
            _entityDisplayFormatted: this.formatDisplay(entityDisplay),
            _actorDisplayFormatted:  this.formatDisplay(actorDisplay),
          };
        });
        this.loading = false;
        this.resolveEntityNames();
      },
      error: () => {
        this.events  = [];
        this.loading = false;
      },
    });
  }

  applyFilters(): void {
    this.adminService.invalidateAuditCache();
    this.loadPage(1);
  }

  onSearch(term: string): void {
    this.searchTerm = term;
    this.adminService.invalidateAuditCache();
    this.loadPage(1);
  }

  /**
   * Resolves any UUIDs still showing as entity names or actor emails.
   * Runs after each page load, batches all unique lookups, deduplicates
   * in-flight requests via the service cache, then patches rows in-place.
   */
  private resolveEntityNames(): void {
    const RESOLVABLE = new Set(['TENANT', 'USER', 'AUTH']);
    const entitySeen = new Set<string>();
    const actorSeen  = new Set<string>();
    const lookups: Array<{ kind: 'entity' | 'actor'; cacheKey: string; entityType: string; id: string }> = [];

    for (const e of this.events) {
      // Entity name missing — queue a lookup
      if (!e.entityName && RESOLVABLE.has((e.entityType || '').toUpperCase())) {
        const k = `${e.entityType}:${e.entityId}`;
        if (!entitySeen.has(k)) {
          entitySeen.add(k);
          lookups.push({ kind: 'entity', cacheKey: k, entityType: e.entityType, id: e.entityId });
        }
      }
      // Actor email missing — queue a USER lookup on the actorId
      if (!e.actorEmail && e.actorId) {
        const k = `USER:${e.actorId}`;
        if (!actorSeen.has(k)) {
          actorSeen.add(k);
          lookups.push({ kind: 'actor', cacheKey: k, entityType: 'USER', id: e.actorId });
        }
      }
    }

    if (!lookups.length) return;

    forkJoin(
      lookups.map(l =>
        this.adminService.resolveEntityName(l.entityType, l.id).pipe(
          map(name => ({ ...l, name }))
        )
      )
    ).subscribe(results => {
      const entityMap = new Map(results.filter(r => r.kind === 'entity').map(r => [r.cacheKey, r.name]));
      const actorMap  = new Map(results.filter(r => r.kind === 'actor' ).map(r => [r.cacheKey, r.name]));

      this.events = this.events.map(e => {
        const entityDisplay = e.entityName
          || entityMap.get(`${e.entityType}:${e.entityId}`)
          || e.entityId;
        const actorDisplay = e.actorEmail
          || actorMap.get(`USER:${e.actorId}`)
          || e.actorId;
        return {
          ...e,
          _entityDisplay: entityDisplay,
          _actorDisplay:  actorDisplay,
          _entityDisplayFormatted: this.formatDisplay(entityDisplay),
          _actorDisplayFormatted:  this.formatDisplay(actorDisplay),
        };
      });
    });
  }

  openDiff(event: any): void {
    this.selectedEvent = event;
    this.showDiff = true;
  }

  closeDiff(): void {
    this.showDiff = false;
    this.selectedEvent = null;
  }

  get diffRows(): DiffRow[] {
    if (!this.selectedEvent) return [];
    const { action, oldValue, newValue, changedFields } = this.selectedEvent;

    if (action === 'UPDATE') {
      const fields: string[] = changedFields?.length
        ? changedFields
        : [...new Set([...Object.keys(oldValue || {}), ...Object.keys(newValue || {})])] as string[];
      return fields.map((f) => ({
        field: f,
        before: oldValue?.[f],
        after: newValue?.[f],
      }));
    }

    if (action === 'CREATE') {
      return Object.keys(newValue || {}).map((f) => ({
        field: f,
        before: undefined,
        after: newValue[f],
      }));
    }

    if (action === 'DELETE') {
      return Object.keys(oldValue || {}).map((f) => ({
        field: f,
        before: oldValue[f],
        after: undefined,
      }));
    }

    return [];
  }

  formatField(key: string): string {
    return key
      .replace(/([A-Z])/g, ' $1')
      .replace(/_/g, ' ')
      .trim()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  formatValue(val: any): string {
    if (val === null || val === undefined) return '—';
    if (typeof val === 'object') return JSON.stringify(val, null, 2);
    return String(val);
  }

  exportCsv(): void {
    // CSV uses raw fields — UUIDs are preserved for audit traceability
    const csvColumns = [
      { key: 'timestamp',    label: 'Timestamp' },
      { key: 'entityType',   label: 'Entity Type' },
      { key: '_entityDisplay', label: 'Entity Name' },
      { key: 'entityId',     label: 'Entity ID (UUID)' },
      { key: 'action',       label: 'Action' },
      { key: '_actorDisplay', label: 'Actor' },
      { key: 'actorId',      label: 'Actor ID (UUID)' },
      { key: 'correlationId', label: 'Correlation ID' },
    ];

    const escape = (v: any): string => {
      if (v == null) return '';
      return `"${String(v).replace(/"/g, '""')}"`;
    };

    const headers = csvColumns.map(c => c.label).join(',');
    const rows = this.events.map(e =>
      csvColumns.map(c => escape(e[c.key])).join(',')
    );
    const csv  = [headers, ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  /** Replaces bare UUIDs with '—' in UI display. Raw UUID values are preserved in _entityDisplay / _actorDisplay for CSV. */
  formatDisplay(value: string | null | undefined): string {
    if (!value) return '—';
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    return uuidPattern.test(value.trim()) ? '—' : value;
  }
}
