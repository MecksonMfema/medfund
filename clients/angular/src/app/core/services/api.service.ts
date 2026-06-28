import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = environment.apiBaseUrl;

  constructor(private http: HttpClient) {}

  /** GET with optional extra HTTP headers (e.g. X-Tenant-ID for super admin cross-tenant queries). */
  getWithHeaders<T>(path: string, headers: Record<string, string>, params?: Record<string, string>): Observable<T> {
    let httpParams = new HttpParams();
    if (params) {
      Object.keys(params).forEach(key => { httpParams = httpParams.set(key, params[key]); });
    }
    return this.http.get<T>(`${this.baseUrl}${path}`, {
      params: httpParams,
      headers: new HttpHeaders(headers),
      withCredentials: true,
    });
  }

  get<T>(path: string, params?: Record<string, string>): Observable<T> {
    let httpParams = new HttpParams();
    if (params) {
      Object.keys(params).forEach((key) => {
        httpParams = httpParams.set(key, params[key]);
      });
    }
    return this.http.get<T>(`${this.baseUrl}${path}`, {
      params: httpParams,
      withCredentials: true,
    });
  }

  /** GET that returns a Blob — for file downloads (PDF, Excel). */
  getBlob(path: string, params?: Record<string, string>): Observable<Blob> {
    let httpParams = new HttpParams();
    if (params) {
      Object.keys(params).forEach((key) => {
        httpParams = httpParams.set(key, params[key]);
      });
    }
    return this.http.get(`${this.baseUrl}${path}`, {
      params: httpParams,
      responseType: 'blob',
      withCredentials: true,
    });
  }

  post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body, {
      withCredentials: true,
    });
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body, {
      withCredentials: true,
    });
  }

  patch<T>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(`${this.baseUrl}${path}`, body, {
      withCredentials: true,
    });
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`, {
      withCredentials: true,
    });
  }

  /** Returns the fully-qualified URL for a given API path. Used for
   *  download-link hrefs where the browser does the streaming and we
   *  can't lean on the HttpClient. */
  absoluteUrl(path: string): string {
    return `${this.baseUrl}${path}`;
  }
}
