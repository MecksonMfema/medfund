import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface Currency {
  code: string;
  name: string;
  symbol: string;
  decimalPlaces: number;
  isActive: boolean;
}

export interface TenantCurrencyConfig {
  id: string;
  tenantId: string;
  currencyCode: string;
  isDefault: boolean;
  isActive: boolean;
  isBillingCurrency: boolean;
  isClaimsCurrency: boolean;
  isPaymentCurrency: boolean;
  exchangeRateSource: string;
}

export interface ExchangeRate {
  id: string;
  baseCurrency: string;
  quoteCurrency: string;
  rate: string;
  rateDate: string;
  source: string;
  tenantId?: string;
  createdAt: string;
}

export interface AddTenantCurrencyPayload {
  currencyCode: string;
  isDefault?: boolean;
  isBillingCurrency?: boolean;
  isClaimsCurrency?: boolean;
  isPaymentCurrency?: boolean;
  exchangeRateSource?: string;
}

export interface UpdateTenantCurrencyPayload {
  isDefault?: boolean;
  isActive?: boolean;
  isBillingCurrency?: boolean;
  isClaimsCurrency?: boolean;
  isPaymentCurrency?: boolean;
  exchangeRateSource?: string;
}

export interface RecordExchangeRatePayload {
  baseCurrency: string;
  quoteCurrency: string;
  rate: string;
  rateDate: string;
  source?: string;
  tenantId?: string;
}

@Injectable({ providedIn: 'root' })
export class CurrencyService {
  constructor(private api: ApiService) {}

  listMaster(activeOnly = true): Observable<Currency[]> {
    return this.api.get<Currency[]>('/api/v1/currencies', { active: String(activeOnly) });
  }

  listForTenant(tenantId: string): Observable<TenantCurrencyConfig[]> {
    return this.api.get<TenantCurrencyConfig[]>(`/api/v1/tenants/${tenantId}/currencies`);
  }

  addToTenant(tenantId: string, body: AddTenantCurrencyPayload): Observable<TenantCurrencyConfig> {
    return this.api.post<TenantCurrencyConfig>(`/api/v1/tenants/${tenantId}/currencies`, body);
  }

  updateTenantCurrency(tenantId: string, configId: string, body: UpdateTenantCurrencyPayload): Observable<TenantCurrencyConfig> {
    return this.api.put<TenantCurrencyConfig>(`/api/v1/tenants/${tenantId}/currencies/${configId}`, body);
  }

  removeFromTenant(tenantId: string, configId: string): Observable<void> {
    return this.api.delete<void>(`/api/v1/tenants/${tenantId}/currencies/${configId}`);
  }

  getRate(base: string, quote: string, date: string, tenantId?: string): Observable<ExchangeRate> {
    const params: Record<string, string> = { base, quote, date };
    if (tenantId) params['tenantId'] = tenantId;
    return this.api.get<ExchangeRate>('/api/v1/exchange-rates', params);
  }

  rateHistory(base: string, quote: string, from: string, to: string, tenantId?: string): Observable<ExchangeRate[]> {
    const params: Record<string, string> = { base, quote, from, to };
    if (tenantId) params['tenantId'] = tenantId;
    return this.api.get<ExchangeRate[]>('/api/v1/exchange-rates/history', params);
  }

  recordRate(body: RecordExchangeRatePayload): Observable<ExchangeRate> {
    return this.api.post<ExchangeRate>('/api/v1/exchange-rates', body);
  }
}
