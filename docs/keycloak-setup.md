# Keycloak Setup & Troubleshooting

## Fixing: "Framing violates Content Security Policy" / "Timeout when waiting for 3rd party check iframe"

This error occurs when your Angular app tries to embed Keycloak's login page in an iframe for silent SSO checks, but Keycloak's Content Security Policy blocks it.

### Root Cause

Keycloak 26 defaults `frame-ancestors` to `'self'`, which blocks iframes from `http://localhost:5100` (your Angular app) trying to load `http://localhost:9080` (Keycloak). Different origins = blocked.

### Solution

#### Step 1: Configure Keycloak Realm Settings

1. Open Keycloak Admin Console: **http://localhost:9080/admin** (admin/admin)
2. Create the realm if it doesn't exist:
   - Click "Create realm" → Name: `medfund-platform` → Create
3. Go to **Realm Settings → Security Defenses** tab
4. Set **Content-Security-Policy** to:
   ```
   frame-src 'self'; frame-ancestors 'self' http://localhost:5100 http://localhost:3000; object-src 'none';
   ```
5. Save

#### Step 2: Create the OIDC Client

1. Go to **Clients → Create client**
2. Settings:
   - **Client ID:** `medfund-web`
   - **Client Protocol:** OpenID Connect
   - **Client authentication:** OFF (public client)
   - **Authorization:** OFF
3. Click **Next → Save**
4. In the client settings:
   - **Valid Redirect URIs:**
     ```
     http://localhost:5100/*
     http://localhost:3000/*
     ```
   - **Valid Post Logout Redirect URIs:**
     ```
     http://localhost:5100/*
     ```
   - **Web Origins:**
     ```
     http://localhost:5100
     http://localhost:3000
     +
     ```
   - **Admin URL:** `http://localhost:5100`
5. Save

#### Step 3: Update Angular Keycloak Init (disable 3rd-party cookie check)

The `3p-cookies/step1.html` error is Keycloak checking if third-party cookies work. In local dev, they often don't. Disable the check:

Edit `src/app/auth/keycloak.init.ts`:

```typescript
import { KeycloakService } from 'keycloak-angular';
import { environment } from '../../environments/environment';

export function initializeKeycloak(keycloak: KeycloakService): () => Promise<boolean> {
  return () =>
    keycloak.init({
      config: {
        url: environment.keycloak.url,
        realm: environment.keycloak.realm,
        clientId: environment.keycloak.clientId,
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
        pkceMethod: 'S256',
        checkLoginIframe: false,  // <-- Disables the iframe check that causes the error
      },
      enableBearerInterceptor: true,
      bearerPrefix: 'Bearer',
      bearerExcludedUrls: ['/assets'],
    });
}
```

The key change is `checkLoginIframe: false` — this disables the iframe-based session check that triggers the CSP violation.

#### Step 4: Create Test User

1. Go to **Users → Add user**
2. Fill in: username, email, first name, last name
3. **Email verified:** ON
4. Save
5. Go to **Credentials** tab → Set password → Temporary: OFF

#### Step 5: Verify

1. Start Angular: `ng serve` (http://localhost:5100)
2. App redirects to Keycloak login
3. Login with test user
4. Redirects back to app with JWT token

### For Production

In production, replace `localhost` URLs with your actual domains:

```
frame-ancestors 'self' https://app.medfund.healthcare https://api.medfund.healthcare;
```

And update the client redirect URIs accordingly.

---

## User Management Architecture

### Question: "There's no User entity in the services — does Keycloak manage all users?"

**Yes — Keycloak is the single source of truth for authentication identity.** But the application services store domain-specific user data.

### How It Works

```
┌─────────────────────────────────┐
│         KEYCLOAK                │
│                                 │
│  Manages:                       │
│  - Login credentials (password) │
│  - MFA (TOTP, Email OTP, SMS)  │
│  - OAuth2 tokens (JWT)         │
│  - Session management           │
│  - Realm roles (member,         │
│    provider, claims_clerk, etc.)│
│  - User enabled/disabled        │
│  - Email verification           │
│  - Password reset flow          │
│                                 │
│  Per-tenant realm:              │
│    medfund-{tenant-slug}        │
│                                 │
│  Returns JWT with:              │
│    sub: keycloak-user-id        │
│    tenant_id: tenant-uuid       │
│    realm_access.roles: [...]    │
│    email, name                  │
└───────────┬─────────────────────┘
            │ JWT
            ▼
┌─────────────────────────────────┐
│      APPLICATION SERVICES       │
│                                 │
│  User Service stores:           │
│  ┌─────────────────────────┐   │
│  │ members table            │   │
│  │  - member_number         │   │
│  │  - first_name, last_name │   │
│  │  - date_of_birth         │   │
│  │  - national_id           │   │
│  │  - phone, address        │   │
│  │  - group_id, scheme_id   │   │
│  │  - enrollment_date       │   │
│  │  - status (lifecycle)    │   │
│  │  - keycloak_user_id ◄────┼── Link to Keycloak
│  └─────────────────────────┘   │
│                                 │
│  ┌─────────────────────────┐   │
│  │ providers table          │   │
│  │  - practice_number       │   │
│  │  - ahfoz_number          │   │
│  │  - specialty             │   │
│  │  - banking_details       │   │
│  │  - keycloak_user_id ◄────┼── Link to Keycloak
│  └─────────────────────────┘   │
│                                 │
│  ┌─────────────────────────┐   │
│  │ roles table              │   │
│  │ role_permissions table   │   │
│  │ user_roles table         │   │ Application-level RBAC
│  └─────────────────────────┘   │ (granular permissions)
└─────────────────────────────────┘
```

### The Split

| Concern | Managed By | Why |
|---------|-----------|-----|
| **Authentication** (who are you?) | Keycloak | Industry-standard OIDC, MFA, session management, social login |
| **Credentials** (password, MFA) | Keycloak | Never store passwords in application DB |
| **OAuth2 Tokens** (JWT) | Keycloak | Token issuance, refresh, revocation |
| **Realm Roles** (member, provider, admin) | Keycloak | Broad role assignment |
| **Domain Identity** (member #, scheme, enrollment) | User Service | Healthcare-specific data |
| **Fine-grained Permissions** (claims:read, finance:approve) | User Service (roles/permissions tables) | Tenant-configurable RBAC |
| **Lifecycle** (enrolled → active → suspended → terminated) | User Service | Business process |

### The Link: `keycloak_user_id`

When a member or provider is created in the User Service:

```java
// In MemberService.enroll():
1. Create member record in DB (status: "enrolled")
2. Call KeycloakSyncService.createUser(realm, email, firstName, lastName, roles)
   → Keycloak creates user with temporary password
   → Returns keycloak_user_id (UUID)
3. Store keycloak_user_id on the member record
4. Member can now login via Keycloak
```

The `keycloak_user_id` field on `members` and `providers` tables links the application domain record to the Keycloak identity.

### Why Not a Separate User Entity?

In healthcare SaaS, "users" are either **members**, **providers**, or **staff** — each with very different data models:

- **Members** have: member numbers, enrollment dates, schemes, dependants, benefit balances
- **Providers** have: practice numbers, AHFOZ credentials, specialties, banking details
- **Staff** have: roles, permissions, department assignments

Creating a generic `users` table would either be too thin (just name + email, duplicating Keycloak) or too wide (one table trying to hold member + provider + staff fields). The current design keeps domain models clean:

- Keycloak handles: "Can this person login? What role are they?"
- User Service handles: "What are their healthcare-specific attributes?"
- The `keycloak_user_id` bridges the two

### Staff Users (Claims Clerks, Finance Officers, etc.)

Staff members don't have records in the `members` or `providers` tables. They exist only in:

1. **Keycloak** — login credentials + realm role (e.g., `claims_clerk`)
2. **user_roles table** — maps their Keycloak user ID to application-level permissions

When a tenant admin creates a staff user:
```
1. POST /api/v1/roles/assign { userId: keycloak-user-id, roleId: claims-clerk-role-id }
2. KeycloakSyncService assigns the Keycloak realm role
3. User can now login and access claims portal
```

Their JWT contains the role, and the API Gateway + Angular app use it for route protection.
