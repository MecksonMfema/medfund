import { KeycloakService } from 'keycloak-angular';

export interface MockKeycloakOptions {
  loggedIn?: boolean;
  roles?: string[];
}

export class MockKeycloakService implements Partial<KeycloakService> {
  private _loggedIn: boolean;
  private _roles: string[];

  loginCalls = 0;

  constructor(opts: MockKeycloakOptions = {}) {
    this._loggedIn = opts.loggedIn ?? true;
    this._roles = opts.roles ?? [];
  }

  // Return shapes mirror the legacy KeycloakService signatures in
  // keycloak-angular 19: `isLoggedIn(): boolean`, `login(): Promise<void>`.
  // Guard code that `await`s isLoggedIn keeps working — `await booleanValue`
  // resolves to the same boolean.
  isLoggedIn = (): boolean => this._loggedIn;
  login = async (): Promise<void> => { this.loginCalls += 1; };
  getUserRoles = (_realm = true): string[] => [...this._roles];

  setLoggedIn(value: boolean): void { this._loggedIn = value; }
  setRoles(roles: string[]): void { this._roles = roles; }
}

export function provideMockKeycloak(opts: MockKeycloakOptions = {}) {
  return { provide: KeycloakService, useValue: new MockKeycloakService(opts) };
}
