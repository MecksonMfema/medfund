import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

// ── Entity shapes — keep aligned with the backend Response DTOs ──────────
// in services/java/user-service/src/main/java/com/medfund/user/dto/.
// Fields the backend may not always populate are optional/nullable so the
// Angular forms don't blow up on partial loads.

export interface Vehicle {
  id: string;
  schemeId: string;
  groupId: string | null;
  ownerMemberId: string | null;
  registrationNumber: string;
  make: string;
  model: string;
  year: number;
  vehicleValue: number;
  bodyType?: string | null;
  usageType?: string | null;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Property {
  id: string;
  schemeId: string;
  groupId: string | null;
  ownerMemberId: string | null;
  propertyName: string;
  address: string;
  sumInsured: number;
  constructionType?: string | null;
  roofType?: string | null;
  locationRiskBand?: 'LOW' | 'MEDIUM' | 'HIGH' | null;
  securityFeaturesCount: number;
  propertyAgeYears?: number | null;
  occupancy?: 'OWNER' | 'TENANT' | 'VACANT' | null;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface LifePolicy {
  id: string;
  schemeId: string;
  groupId: string | null;
  insuredMemberId: string;
  policyNumber: string;
  sumAssured: number;
  occupationHazardClass?: string | null;
  termMonths: number;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface FuneralPolicy {
  id: string;
  schemeId: string;
  groupId: string | null;
  principalMemberId: string;
  policyNumber: string;
  coverAmount: number;
  livesCovered: number;
  healthDeclaration?: string | null;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface TravelPolicy {
  id: string;
  schemeId: string;
  groupId: string | null;
  travelerMemberId: string;
  policyNumber: string;
  tripStartDate: string;
  tripEndDate: string;
  destinationBand?: string | null;
  coverageLevel?: string | null;
  preExistingDeclared: boolean;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface DisabilityPolicy {
  id: string;
  schemeId: string;
  groupId: string | null;
  insuredMemberId: string;
  policyNumber: string;
  occupationHazardClass?: string | null;
  waitingPeriodDays: number;
  benefitPeriod?: string | null;
  monthlyBenefit: number;
  status: string;
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

// ── Server-side paginated row shapes — mirror user-service *Row records ──
// The "page" endpoints return these instead of the raw entity DTOs above;
// they include pre-joined display fields (schemeName, ownerMemberName,
// insuredMemberName, etc.) so the tables never render raw UUIDs.

export interface PoliciesPageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface VehicleRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  ownerMemberId: string | null;
  ownerMemberName: string | null;
  registrationNumber: string;
  make: string;
  model: string;
  year: number;
  vehicleValue: number;
  bodyType: string | null;
  usageType: string | null;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface PropertyRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  ownerMemberId: string | null;
  ownerMemberName: string | null;
  propertyName: string;
  address: string | null;
  sumInsured: number;
  constructionType: string | null;
  occupancy: string | null;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface LifePolicyRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  insuredMemberId: string;
  insuredMemberName: string | null;
  policyNumber: string;
  sumAssured: number;
  occupationHazardClass: string | null;
  termMonths: number;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface FuneralPolicyRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  principalMemberId: string;
  principalMemberName: string | null;
  policyNumber: string;
  coverAmount: number;
  livesCovered: number;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface TravelPolicyRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  travelerMemberId: string;
  travelerMemberName: string | null;
  policyNumber: string;
  tripStartDate: string;
  tripEndDate: string;
  destinationBand: string | null;
  coverageLevel: string | null;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface DisabilityPolicyRow {
  id: string;
  schemeId: string;
  schemeName: string | null;
  insuredMemberId: string;
  insuredMemberName: string | null;
  policyNumber: string;
  occupationHazardClass: string | null;
  waitingPeriodDays: number;
  benefitPeriod: string | null;
  monthlyBenefit: number;
  status: string;
  billingOverrideAmount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface PolicyPageParams {
  status?: string;
  schemeId?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

/**
 * One service per backend domain. The Part-3a user-service exposes six
 * REST surfaces (/api/v1/{vehicles,properties,life-policies,funeral-
 * policies,travel-policies,disability-policies}); grouping all of them
 * here means consumers import a single token instead of six.
 *
 * The list*Paged methods hit the /page endpoints and receive Row DTOs
 * with pre-joined scheme + member names. The plain list* methods still
 * exist for pickers, forms, and legacy consumers.
 */
@Injectable({ providedIn: 'root' })
export class PoliciesService {
  constructor(private api: ApiService) {}

  // ── Paginated lists (server-side) ──────────────────────────────────────
  listVehiclesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<VehicleRow>>('/vehicles/page', this.pageParams(opts));
  }
  listPropertiesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<PropertyRow>>('/properties/page', this.pageParams(opts));
  }
  listLifePoliciesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<LifePolicyRow>>('/life-policies/page', this.pageParams(opts));
  }
  listFuneralPoliciesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<FuneralPolicyRow>>('/funeral-policies/page', this.pageParams(opts));
  }
  listTravelPoliciesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<TravelPolicyRow>>('/travel-policies/page', this.pageParams(opts));
  }
  listDisabilityPoliciesPaged(opts: PolicyPageParams = {}) {
    return this.api.get<PoliciesPageResponse<DisabilityPolicyRow>>('/disability-policies/page', this.pageParams(opts));
  }

  private pageParams(opts: PolicyPageParams): Record<string, string> {
    const p: Record<string, string> = {
      page: String(opts.page ?? 0),
      size: String(opts.size ?? 50),
    };
    if (opts.status)        p['status']        = opts.status;
    if (opts.schemeId)      p['schemeId']      = opts.schemeId;
    if (opts.q)             p['q']             = opts.q;
    if (opts.sortKey)       p['sortKey']       = opts.sortKey;
    if (opts.sortDirection) p['sortDirection'] = opts.sortDirection;
    return p;
  }

  // ── VEHICLES (MOTOR) ────────────────────────────────────────────────────
  listVehicles():                          Observable<Vehicle[]>      { return this.api.get<Vehicle[]>('/vehicles'); }
  getVehicle(id: string):                  Observable<Vehicle>        { return this.api.get<Vehicle>(`/vehicles/${id}`); }
  searchVehicles(q: string):               Observable<Vehicle[]>      { return this.api.get<Vehicle[]>('/vehicles/search', { q }); }
  createVehicle(data: any):                Observable<Vehicle>        { return this.api.post<Vehicle>('/vehicles', data); }
  updateVehicle(id: string, data: any):    Observable<Vehicle>        { return this.api.put<Vehicle>(`/vehicles/${id}`, data); }
  suspendVehicle(id: string):              Observable<Vehicle>        { return this.api.post<Vehicle>(`/vehicles/${id}/suspend`, {}); }
  terminateVehicle(id: string):            Observable<Vehicle>        { return this.api.post<Vehicle>(`/vehicles/${id}/terminate`, {}); }
  clearVehicleOverride(id: string):        Observable<Vehicle>        { return this.api.post<Vehicle>(`/vehicles/${id}/clear-billing-override`, {}); }

  // ── PROPERTIES ──────────────────────────────────────────────────────────
  listProperties():                          Observable<Property[]>   { return this.api.get<Property[]>('/properties'); }
  getProperty(id: string):                   Observable<Property>     { return this.api.get<Property>(`/properties/${id}`); }
  searchProperties(q: string):               Observable<Property[]>   { return this.api.get<Property[]>('/properties/search', { q }); }
  createProperty(data: any):                 Observable<Property>     { return this.api.post<Property>('/properties', data); }
  updateProperty(id: string, data: any):     Observable<Property>     { return this.api.put<Property>(`/properties/${id}`, data); }
  suspendProperty(id: string):               Observable<Property>     { return this.api.post<Property>(`/properties/${id}/suspend`, {}); }
  terminateProperty(id: string):             Observable<Property>     { return this.api.post<Property>(`/properties/${id}/terminate`, {}); }
  clearPropertyOverride(id: string):         Observable<Property>     { return this.api.post<Property>(`/properties/${id}/clear-billing-override`, {}); }

  // ── LIFE POLICIES ───────────────────────────────────────────────────────
  listLifePolicies():                        Observable<LifePolicy[]> { return this.api.get<LifePolicy[]>('/life-policies'); }
  getLifePolicy(id: string):                 Observable<LifePolicy>   { return this.api.get<LifePolicy>(`/life-policies/${id}`); }
  searchLifePolicies(q: string):             Observable<LifePolicy[]> { return this.api.get<LifePolicy[]>('/life-policies/search', { q }); }
  createLifePolicy(data: any):               Observable<LifePolicy>   { return this.api.post<LifePolicy>('/life-policies', data); }
  updateLifePolicy(id: string, data: any):   Observable<LifePolicy>   { return this.api.put<LifePolicy>(`/life-policies/${id}`, data); }
  suspendLifePolicy(id: string):             Observable<LifePolicy>   { return this.api.post<LifePolicy>(`/life-policies/${id}/suspend`, {}); }
  terminateLifePolicy(id: string):           Observable<LifePolicy>   { return this.api.post<LifePolicy>(`/life-policies/${id}/terminate`, {}); }
  clearLifePolicyOverride(id: string):       Observable<LifePolicy>   { return this.api.post<LifePolicy>(`/life-policies/${id}/clear-billing-override`, {}); }

  // ── FUNERAL POLICIES ────────────────────────────────────────────────────
  listFuneralPolicies():                       Observable<FuneralPolicy[]> { return this.api.get<FuneralPolicy[]>('/funeral-policies'); }
  getFuneralPolicy(id: string):                Observable<FuneralPolicy>   { return this.api.get<FuneralPolicy>(`/funeral-policies/${id}`); }
  searchFuneralPolicies(q: string):            Observable<FuneralPolicy[]> { return this.api.get<FuneralPolicy[]>('/funeral-policies/search', { q }); }
  createFuneralPolicy(data: any):              Observable<FuneralPolicy>   { return this.api.post<FuneralPolicy>('/funeral-policies', data); }
  updateFuneralPolicy(id: string, data: any):  Observable<FuneralPolicy>   { return this.api.put<FuneralPolicy>(`/funeral-policies/${id}`, data); }
  suspendFuneralPolicy(id: string):            Observable<FuneralPolicy>   { return this.api.post<FuneralPolicy>(`/funeral-policies/${id}/suspend`, {}); }
  terminateFuneralPolicy(id: string):          Observable<FuneralPolicy>   { return this.api.post<FuneralPolicy>(`/funeral-policies/${id}/terminate`, {}); }
  clearFuneralPolicyOverride(id: string):      Observable<FuneralPolicy>   { return this.api.post<FuneralPolicy>(`/funeral-policies/${id}/clear-billing-override`, {}); }

  // ── TRAVEL POLICIES ─────────────────────────────────────────────────────
  listTravelPolicies():                        Observable<TravelPolicy[]>  { return this.api.get<TravelPolicy[]>('/travel-policies'); }
  getTravelPolicy(id: string):                 Observable<TravelPolicy>    { return this.api.get<TravelPolicy>(`/travel-policies/${id}`); }
  searchTravelPolicies(q: string):             Observable<TravelPolicy[]>  { return this.api.get<TravelPolicy[]>('/travel-policies/search', { q }); }
  createTravelPolicy(data: any):               Observable<TravelPolicy>    { return this.api.post<TravelPolicy>('/travel-policies', data); }
  updateTravelPolicy(id: string, data: any):   Observable<TravelPolicy>    { return this.api.put<TravelPolicy>(`/travel-policies/${id}`, data); }
  suspendTravelPolicy(id: string):             Observable<TravelPolicy>    { return this.api.post<TravelPolicy>(`/travel-policies/${id}/suspend`, {}); }
  terminateTravelPolicy(id: string):           Observable<TravelPolicy>    { return this.api.post<TravelPolicy>(`/travel-policies/${id}/terminate`, {}); }
  clearTravelPolicyOverride(id: string):       Observable<TravelPolicy>    { return this.api.post<TravelPolicy>(`/travel-policies/${id}/clear-billing-override`, {}); }

  // ── DISABILITY POLICIES ─────────────────────────────────────────────────
  listDisabilityPolicies():                       Observable<DisabilityPolicy[]> { return this.api.get<DisabilityPolicy[]>('/disability-policies'); }
  getDisabilityPolicy(id: string):                Observable<DisabilityPolicy>   { return this.api.get<DisabilityPolicy>(`/disability-policies/${id}`); }
  searchDisabilityPolicies(q: string):            Observable<DisabilityPolicy[]> { return this.api.get<DisabilityPolicy[]>('/disability-policies/search', { q }); }
  createDisabilityPolicy(data: any):              Observable<DisabilityPolicy>   { return this.api.post<DisabilityPolicy>('/disability-policies', data); }
  updateDisabilityPolicy(id: string, data: any):  Observable<DisabilityPolicy>   { return this.api.put<DisabilityPolicy>(`/disability-policies/${id}`, data); }
  suspendDisabilityPolicy(id: string):            Observable<DisabilityPolicy>   { return this.api.post<DisabilityPolicy>(`/disability-policies/${id}/suspend`, {}); }
  terminateDisabilityPolicy(id: string):          Observable<DisabilityPolicy>   { return this.api.post<DisabilityPolicy>(`/disability-policies/${id}/terminate`, {}); }
  clearDisabilityPolicyOverride(id: string):      Observable<DisabilityPolicy>   { return this.api.post<DisabilityPolicy>(`/disability-policies/${id}/clear-billing-override`, {}); }
}
