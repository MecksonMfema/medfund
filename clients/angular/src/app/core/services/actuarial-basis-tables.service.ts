import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Metadata row returned by ai-service's
 * {@code GET /api/v1/actuarial/basis-tables/list}. Mirrors
 * {@code BasisTableMetadata} in
 * {@code services/python/ai-service/app/actuarial/basis_loader.py}.
 */
export interface BasisTableMetadata {
  name: string;
  displayName: string;
  category: 'mortality' | 'morbidity';
  jurisdictionHints: string[];
  defaultLine: string | null;
  source: string | null;
}

// Wire-shape: pydantic emits snake_case. Angular maps to camelCase at
// the service boundary so the rest of the app can consume idiomatic TS.
interface BasisTableMetadataWire {
  name: string;
  display_name: string;
  category: 'mortality' | 'morbidity';
  jurisdiction_hints?: string[];
  default_line?: string | null;
  source?: string | null;
}

/**
 * HTTP wrapper for ai-service's actuarial basis-table catalogue. Feeds the
 * tenant-admin mortality + morbidity basis-picker dropdowns. The YAML tree
 * under {@code services/python/ai-service/app/actuarial/basis_tables/} is
 * the single source of truth.
 */
@Injectable({ providedIn: 'root' })
export class ActuarialBasisTablesService {
  constructor(private api: ApiService) {}

  list(category: 'mortality' | 'morbidity'): Observable<BasisTableMetadata[]> {
    return new Observable<BasisTableMetadata[]>((subscriber) => {
      const sub = this.api
        .get<BasisTableMetadataWire[]>(`/actuarial/basis-tables/list`, { category })
        .subscribe({
          next: (wire) => {
            subscriber.next(wire.map(this.fromWire));
            subscriber.complete();
          },
          error: (err) => subscriber.error(err),
        });
      return () => sub.unsubscribe();
    });
  }

  private fromWire(w: BasisTableMetadataWire): BasisTableMetadata {
    return {
      name: w.name,
      displayName: w.display_name,
      category: w.category,
      jurisdictionHints: w.jurisdiction_hints ?? [],
      defaultLine: w.default_line ?? null,
      source: w.source ?? null,
    };
  }
}
