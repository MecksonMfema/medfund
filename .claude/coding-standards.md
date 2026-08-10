# Coding Standards

## General Principles (All Languages)

1. **Fail fast, fail loudly.** Validate at boundaries (API input, Kafka events, external API responses). Trust internal code.
2. **No silent failures.** Every error is either handled or propagated. No empty catch blocks.
3. **Structured logging.** JSON format. Include `tenant_id`, `user_id`, `correlation_id` (from OpenTelemetry trace) in every log line.
4. **Tests are required.** No PR merges without tests. Minimum 80% coverage for business logic.
5. **No hardcoded configuration.** Environment variables or config files. No secrets in code.
6. **Consistent naming.** See per-language conventions below.
7. **Every API endpoint must have Swagger documentation.** See [Swagger/OpenAPI Standards](#swaggeropenapi-standards) section below.
8. **Every entity mutation must be audit-logged.** See [Audit Logging Standards](#audit-logging-standards) section below. Every CREATE, UPDATE, DELETE must capture who did it, when, what changed, and from which service.

## Swagger/OpenAPI Standards

Every REST endpoint across all services **must** be fully documented in OpenAPI 3.1. No endpoint ships without Swagger documentation.

### Required Per Endpoint

| Element | Required | Description |
|---------|----------|-------------|
| **Summary** | Yes | Short one-line description (shown in Swagger UI endpoint list) |
| **Description** | Yes (non-trivial endpoints) | Detailed explanation of behavior, side effects, business rules |
| **Tags** | Yes | Group by resource (e.g., `Claims`, `Payments`, `Members`) |
| **Request body schema** | Yes (POST/PATCH/PUT) | Full schema with field descriptions, types, constraints, required flags |
| **Response schemas** | Yes (all status codes) | Schema for 200/201/204 + all error responses (400, 401, 403, 404, 409, 422, 500) |
| **Example payloads** | Yes | At least one request example and one response example per endpoint |
| **Authentication** | Yes | Security scheme declaration (Bearer JWT) |
| **Parameters** | Yes (if any) | Path params, query params with types, descriptions, required flags, defaults |
| **Pagination** | Yes (list endpoints) | Document cursor/offset params, page size limits, response envelope |

### Per-Language Implementation

#### Java (Spring Boot) — SpringDoc OpenAPI

```java
@Operation(
    summary = "Adjudicate a claim",
    description = "Runs the claim through the 6-stage adjudication pipeline. "
        + "Returns the adjudication result with AI recommendation if enabled.",
    tags = {"Claims"}
)
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Adjudication completed",
        content = @Content(schema = @Schema(implementation = AdjudicationResultDto.class))),
    @ApiResponse(responseCode = "404", description = "Claim not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "Claim already adjudicated",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "422", description = "Claim not in adjudicable state",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
})
@PostMapping("/{claimId}/adjudicate")
public ResponseEntity<AdjudicationResultDto> adjudicate(
    @Parameter(description = "UUID of the claim", required = true)
    @PathVariable UUID claimId,
    @Valid @RequestBody AdjudicationRequestDto request
) { ... }
```

- Use `springdoc-openapi-starter-webflux-ui` (Spring Boot 3.x + WebFlux)
- Swagger UI available at: `/swagger-ui.html`
- OpenAPI JSON spec at: `/v3/api-docs`
- Configure info, servers, security schemes in `OpenApiConfig.java`
- Use `@Schema` annotations on DTOs with `description`, `example`, `requiredMode`

```java
public record AdjudicationRequestDto(
    @Schema(description = "Override AI recommendation", example = "false")
    boolean overrideAi,

    @Schema(description = "Manual decision if overriding AI", example = "APPROVE")
    @Nullable AdjudicationDecision manualDecision,

    @Schema(description = "Reason for override", example = "Pre-auth confirmed via phone")
    @Nullable String overrideReason
) {}
```

#### Go (Fiber) — Swaggo

```go
// AdjudicateClaim godoc
// @Summary      Submit a notification
// @Description  Sends a notification via the configured channel (email, SMS, push)
// @Tags         Notifications
// @Accept       json
// @Produce      json
// @Param        request body NotificationRequest true "Notification payload"
// @Success      202 {object} NotificationResponse "Notification accepted for delivery"
// @Failure      400 {object} ProblemDetail "Invalid request"
// @Failure      401 {object} ProblemDetail "Unauthorized"
// @Failure      422 {object} ProblemDetail "Channel not configured for tenant"
// @Router       /api/v2/notifications [post]
// @Security     BearerAuth
func (h *NotificationHandler) Send(c *fiber.Ctx) error { ... }
```

- Use `swaggo/swag` + `swaggo/fiber-swagger`
- Run `swag init` during build to generate spec
- Swagger UI at: `/swagger/`
- Document struct fields with `swaggertype`, `example`, `validate` tags

#### Python (FastAPI) — Built-in OpenAPI

```python
@router.post(
    "/predictions/adjudication",
    response_model=AdjudicationPrediction,
    status_code=200,
    summary="Get AI adjudication recommendation",
    description="Analyzes a claim using ML models and Claude reasoning. "
        "Returns recommendation, confidence score, and explanation.",
    tags=["AI Predictions"],
    responses={
        404: {"model": ProblemDetail, "description": "Claim not found"},
        422: {"model": ProblemDetail, "description": "Insufficient data for prediction"},
        503: {"model": ProblemDetail, "description": "AI service unavailable"},
    },
)
async def predict_adjudication(
    request: AdjudicationPredictionRequest,
    session: AsyncSession = Depends(get_tenant_session),
) -> AdjudicationPrediction:
    ...
```

- FastAPI generates OpenAPI 3.1 automatically from type hints and Pydantic models
- Swagger UI at: `/docs` (default)
- ReDoc at: `/redoc`
- Use `Field(description=..., example=...)` on all Pydantic model fields

```python
class AdjudicationPrediction(BaseModel):
    claim_id: UUID = Field(description="ID of the analyzed claim")
    recommendation: Literal["APPROVE", "REJECT", "REVIEW"] = Field(
        description="AI recommendation", example="APPROVE"
    )
    confidence: Decimal = Field(
        description="Confidence score 0.0-1.0", example="0.87", ge=0, le=1
    )
    explanation: str = Field(
        description="Human-readable reasoning from Claude",
        example="Claim matches tariff K35.0 appendicectomy at standard rate..."
    )
    fraud_risk: Decimal = Field(
        description="Fraud risk score 0.0-1.0", example="0.12", ge=0, le=1
    )
    flagged_issues: list[str] = Field(
        description="List of flagged concerns", default_factory=list
    )
```

#### Elixir (Phoenix) — OpenApiSpex

```elixir
defmodule LiveDashboardWeb.DashboardController do
  use LiveDashboardWeb, :controller
  use OpenApiSpex.ControllerSpecs

  tags ["Dashboard"]
  security [%{"bearerAuth" => []}]

  operation :index,
    summary: "Get dashboard metrics",
    description: "Returns real-time dashboard metrics for the tenant",
    parameters: [
      tenant_id: [in: :header, name: "X-Tenant-ID", required: true, schema: %OpenApiSpex.Schema{type: :string}]
    ],
    responses: [
      ok: {"Dashboard metrics", "application/json", DashboardMetricsSchema},
      unauthorized: {"Unauthorized", "application/json", ProblemDetailSchema}
    ]

  def index(conn, _params), do: ...
end
```

- Use `open_api_spex` hex package
- Swagger UI at: `/swaggerui`
- Define schemas as `OpenApiSpex.Schema` modules

### Swagger UI Access Per Service

| Service | Language | Swagger UI URL | Spec URL |
|---------|----------|---------------|----------|
| Claims Service | Java | `/swagger-ui.html` | `/v3/api-docs` |
| Contributions Service | Java | `/swagger-ui.html` | `/v3/api-docs` |
| Finance Service | Java | `/swagger-ui.html` | `/v3/api-docs` |
| Tenancy Service | Java | `/swagger-ui.html` | `/v3/api-docs` |
| Rules Engine | Java | `/swagger-ui.html` | `/v3/api-docs` |
| User Service | Java | `/swagger-ui.html` | `/v3/api-docs` |
| API Gateway | Go | `/swagger/` | `/swagger/doc.json` |
| Notification Service | Go | `/swagger/` | `/swagger/doc.json` |
| Audit Service | Go | `/swagger/` | `/swagger/doc.json` |
| File Service | Go | `/swagger/` | `/swagger/doc.json` |
| Live Dashboard | Elixir | `/swaggerui` | `/api/openapi` |
| Chat Service | Elixir | `/swaggerui` | `/api/openapi` |
| AI Service | Python | `/docs` | `/openapi.json` |

### API Gateway Aggregated Spec

The API Gateway should aggregate all downstream OpenAPI specs into a **single unified spec** available at `/swagger-ui.html`. This gives consumers a single place to browse all InsureFlow APIs. Use a spec aggregation tool or custom middleware that fetches and merges specs from each service on startup.

### CI Enforcement

- **Swagger spec diff** in PRs: CI generates the OpenAPI spec and compares against the previous version. Breaking changes (removed endpoints, changed required fields) must be flagged.
- **Spec validation**: CI runs `swagger-cli validate` or equivalent to ensure the generated spec is valid OpenAPI 3.1.
- **No undocumented endpoints**: CI check that every controller method has Swagger annotations. Use linting rules or custom scripts per language.

## Java (Spring Boot Services)

### Project Structure
```
claims-service/
├── src/main/java/com/medfund/claims/
│   ├── ClaimsApplication.java
│   ├── config/                    # Spring configuration classes
│   ├── controller/                # REST controllers (thin — delegate to services)
│   ├── service/                   # Business logic
│   ├── repository/                # Spring Data JPA repositories
│   ├── entity/                    # JPA entities
│   ├── dto/                       # Request/response DTOs (records)
│   ├── mapper/                    # Entity ↔ DTO mappers (MapStruct)
│   ├── event/                     # Kafka event producers/consumers
│   ├── exception/                 # Custom exceptions + global handler
│   └── grpc/                      # gRPC service implementations
├── src/main/resources/
│   ├── application.yml
│   ├── application-staging.yml
│   ├── application-prod.yml
│   └── db/migration/             # Flyway SQL migrations
├── src/test/java/
│   ├── unit/                     # Unit tests (Mockito)
│   └── integration/              # Integration tests (Testcontainers)
├── build.gradle.kts
└── Dockerfile
```

### Conventions
- **Java 21 features**: Use records for DTOs, sealed interfaces for type hierarchies, pattern matching
- **Reactive stack**: Spring WebFlux + Project Reactor. All controllers return `Mono<T>` or `Flux<T>`. No blocking calls on the event loop. Use Netty (not Tomcat) as embedded server
- **Database**: Spring Data R2DBC for non-blocking database access. Use `DatabaseClient` for complex queries. No JPA/Hibernate blocking calls
- **Naming**: `PascalCase` classes, `camelCase` methods/variables, `SCREAMING_SNAKE` constants
- **DTOs**: Use Java `record` types. Immutable. Validated with Jakarta annotations
- **Entities**: R2DBC entities annotated with `@Table`. UUID v7 primary keys. Audit fields (`createdAt`, `updatedAt`, `createdBy`)
- **Services**: Interface + implementation pattern only when multiple implementations exist. Otherwise, concrete classes. All return `Mono<T>` or `Flux<T>`
- **Exceptions**: Custom exception hierarchy extending `RuntimeException`. Global `@ControllerAdvice` with `@ExceptionHandler` returns problem details (RFC 9457)
- **Money**: Always `BigDecimal` with `RoundingMode.HALF_EVEN`. Never `double`
- **Tests**: JUnit 5 + Mockito for unit tests. `WebTestClient` for reactive endpoint tests. Testcontainers (PostgreSQL, Kafka, Redis) for integration tests
- **Blocking operations**: If you must call a blocking API (e.g., Drools rules engine, legacy library), wrap in `Mono.fromCallable(() -> ...).subscribeOn(Schedulers.boundedElastic())`. Never block on the Netty event loop

### Reactive Patterns

```java
// Controller — return Mono/Flux, never block
@RestController
@RequestMapping("/api/v2/claims")
public class ClaimController {

    @PostMapping
    public Mono<ResponseEntity<ClaimDto>> submitClaim(
        @Valid @RequestBody Mono<ClaimSubmissionRequest> request
    ) {
        return request
            .flatMap(req -> claimService.submit(req))
            .map(claim -> ResponseEntity.status(HttpStatus.CREATED).body(claim));
    }

    @GetMapping
    public Flux<ClaimDto> listClaims(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return claimService.findAll(PageRequest.of(page, size));
    }
}

// Service — compose reactive pipelines
@Service
public class ClaimService {

    public Mono<ClaimDto> submit(ClaimSubmissionRequest request) {
        return memberRepository.findById(request.memberId())
            .switchIfEmpty(Mono.error(new MemberNotFoundException(request.memberId())))
            .flatMap(member -> validateEligibility(member))
            .flatMap(member -> claimRepository.save(toEntity(request, member)))
            .flatMap(claim -> publishEvent(claim).thenReturn(claim))
            .map(ClaimMapper::toDto);
    }
}

// R2DBC Repository
public interface ClaimRepository extends ReactiveCrudRepository<Claim, UUID> {

    @Query("SELECT * FROM claims WHERE member_id = :memberId AND status = :status")
    Flux<Claim> findByMemberIdAndStatus(UUID memberId, String status);
}

// Tenant-aware R2DBC connection — set schema per request
@Component
public class TenantAwareConnectionFactory implements ConnectionFactory {

    public Mono<Connection> create() {
        return delegate.create()
            .flatMap(conn -> {
                String tenantId = TenantContext.getCurrentTenant();
                return Mono.from(conn.createStatement(
                    "SET search_path TO tenant_" + tenantId
                ).execute()).thenReturn(conn);
            });
    }
}

// Wrapping blocking Drools calls
public Mono<AdjudicationResult> evaluateRules(Claim claim) {
    return Mono.fromCallable(() -> {
        // Drools is blocking — run on boundedElastic scheduler
        KieSession session = kieContainer.newKieSession();
        session.insert(claim);
        session.fireAllRules();
        return extractResult(session);
    }).subscribeOn(Schedulers.boundedElastic());
}
```

### Logging
```java
// Use SLF4J with structured logging (Logback + JSON encoder)
log.info("Claim adjudicated", Map.of(
    "claimId", claim.getId(),
    "tenantId", tenantContext.getTenantId(),
    "decision", "approved",
    "aiConfidence", prediction.getConfidence()
));
```

## Go (Fiber Services)

### Project Structure
```
api-gateway/
├── cmd/
│   └── server/
│       └── main.go                # Entrypoint
├── internal/
│   ├── config/                    # Configuration loading
│   ├── handler/                   # HTTP handlers (Fiber)
│   ├── middleware/                 # Custom middleware (auth, tenant, logging)
│   ├── service/                   # Business logic
│   ├── repository/                # Database access (pgx)
│   ├── model/                     # Domain models
│   ├── kafka/                     # Kafka producer/consumer
│   └── grpc/                      # gRPC client/server
├── pkg/                           # Shared utilities (exported)
├── go.mod
├── go.sum
├── Makefile
└── Dockerfile
```

### Conventions
- **Naming**: `PascalCase` exported, `camelCase` unexported, `snake_case` files
- **Error handling**: Return errors, don't panic. Wrap errors with `fmt.Errorf("context: %w", err)`
- **Interfaces**: Define at the consumer, not the provider. Small interfaces (1-3 methods)
- **Context**: Pass `context.Context` as first parameter. Carry tenant ID, correlation ID
- **Money**: Use `shopspring/decimal`. Never `float64`
- **Tests**: Table-driven tests. `testify/assert` for assertions. Testcontainers for integration
- **Linting**: `golangci-lint` with strict config (errcheck, gosec, govet, staticcheck)

### Logging
```go
// Use zerolog for structured JSON logging
log.Info().
    Str("tenantId", tenantID).
    Str("action", "notification_sent").
    Str("channel", "email").
    Str("recipient", email).
    Msg("notification delivered")
```

## Elixir (Phoenix Services)

### Project Structure
```
live_dashboard/
├── lib/
│   ├── live_dashboard/
│   │   ├── application.ex         # OTP application
│   │   ├── repo.ex                # Ecto repo
│   │   ├── schemas/               # Ecto schemas
│   │   ├── contexts/              # Business logic (Phoenix contexts)
│   │   └── kafka/                 # Broadway consumers
│   └── live_dashboard_web/
│       ├── channels/              # Phoenix channels
│       ├── live/                   # LiveView modules
│       ├── controllers/           # REST controllers
│       ├── plugs/                 # Custom plugs (middleware)
│       └── router.ex
├── test/
├── config/
├── mix.exs
└── Dockerfile
```

### Conventions
- **Naming**: `PascalCase` modules, `snake_case` functions/variables, `snake_case` files
- **Pattern matching**: Use extensively for control flow and data extraction
- **Contexts**: Phoenix contexts for business logic boundaries
- **GenServer**: For stateful processes (presence tracking, rate limiting)
- **Supervision trees**: Let it crash philosophy with proper supervisors
- **Tests**: ExUnit with doctests. Mox for mocking behaviours

## Python (FastAPI AI Service)

### Project Structure
```
ai-service/
├── app/
│   ├── main.py                    # FastAPI app creation
│   ├── config.py                  # Settings (pydantic-settings)
│   ├── api/
│   │   ├── routes/                # Route handlers
│   │   └── deps.py                # Dependency injection
│   ├── models/                    # Pydantic schemas (request/response)
│   ├── ml/
│   │   ├── adjudication/          # Adjudication ML models
│   │   ├── fraud/                 # Fraud detection models
│   │   ├── ocr/                   # OCR pipeline
│   │   └── chatbot/               # Claude chatbot logic
│   ├── services/                  # Business logic
│   ├── db/                        # SQLAlchemy models + session
│   └── kafka/                     # Consumer/producer
├── tests/
├── pyproject.toml                 # uv / pip configuration
└── Dockerfile
```

### Conventions
- **Naming**: `PascalCase` classes, `snake_case` everything else
- **Type hints**: Required on all functions. `mypy --strict` compatible
- **Pydantic**: All API schemas are Pydantic BaseModel with strict mode
- **Async**: Use `async def` for all route handlers. `asyncio` for I/O
- **Money**: `decimal.Decimal`. Never `float`
- **Tests**: pytest with async support (pytest-asyncio). httpx for API tests
- **Linting**: Ruff for formatting + linting. pyright for type checking

## Angular (Web Application)

### Project Structure
```
web-admin/
├── src/
│   ├── app/
│   │   ├── core/                  # Singleton services, guards, interceptors
│   │   │   ├── auth/              # Keycloak OIDC service
│   │   │   ├── interceptors/      # HTTP interceptors (auth, tenant, error)
│   │   │   └── guards/            # Route guards (role-based)
│   │   ├── shared/                # Shared components, pipes, directives
│   │   ├── features/              # Lazy-loaded feature modules
│   │   │   ├── super-admin/       # Super admin portal
│   │   │   ├── tenant-admin/      # Tenant admin portal
│   │   │   ├── claims/            # Claims management
│   │   │   ├── finance/           # Finance management
│   │   │   ├── contributions/     # Contributions management
│   │   │   └── providers/         # Provider portal
│   │   ├── app.component.ts
│   │   ├── app.config.ts
│   │   └── app.routes.ts
│   ├── environments/
│   └── styles/                    # Global Tailwind + theme variables
├── angular.json
├── tsconfig.json
├── tailwind.config.js
└── Dockerfile
```

### Conventions
- **Standalone components** (no NgModules for feature components)
- **Signals** for reactive state (Angular 19 signals API)
- **NgRx Signal Store** for complex state management
- **Lazy loading** for all feature routes
- **OnPush change detection** everywhere
- **Strict TypeScript**: no `any`, no `@ts-ignore`
- **Testing**: Jasmine + Karma for unit tests, Cypress for E2E

## Flutter (Mobile + Web)

### Project Structure
```
mobile-app/
├── lib/
│   ├── main.dart
│   ├── app/
│   │   ├── app.dart               # MaterialApp configuration
│   │   └── routes.dart            # go_router route definitions
│   ├── core/
│   │   ├── auth/                  # Keycloak OIDC
│   │   ├── network/               # Dio HTTP client, interceptors
│   │   ├── storage/               # Local storage (Hive/Isar)
│   │   └── theme/                 # Per-tenant theming
│   ├── features/
│   │   ├── dashboard/
│   │   ├── claims/
│   │   ├── benefits/
│   │   ├── payments/
│   │   ├── profile/
│   │   ├── chat/
│   │   └── membership_card/
│   ├── shared/
│   │   ├── widgets/               # Reusable widgets
│   │   └── models/                # Data models (freezed)
│   └── providers/                 # Riverpod providers
├── test/
├── pubspec.yaml
└── Dockerfile                     # For Flutter web build
```

### Conventions
- **Riverpod** for all state management (no setState except trivial cases)
- **freezed** for immutable data classes
- **go_router** for navigation with guards
- **Dio interceptors** for auth token, tenant header, error handling
- **Null safety** enforced (no `!` operator without justification)
- **Testing**: widget tests for all screens, integration tests for critical flows

## Audit Logging Standards

Every entity mutation across all services must produce an immutable audit event. Additionally, all security-relevant events (authentication, authorization) must be logged. This is a **non-negotiable compliance requirement** for healthcare platforms.

### Audit Event Schema

Every audit event published to Kafka (`medfund.audit.*` topics) and stored in the per-tenant `audit_events` table follows this structure:

```json
{
  "event_id": "uuid-v7",
  "tenant_id": "uuid",
  "actor": {
    "user_id": "uuid",
    "username": "john.doe",
    "role": "claims_clerk",
    "ip_address": "192.168.1.100",
    "user_agent": "Mozilla/5.0...",
    "session_id": "keycloak-session-id"
  },
  "action": "UPDATE",
  "entity_type": "claim",
  "entity_id": "uuid",
  "service": "claims-service",
  "timestamp": "2026-03-27T14:30:00.000Z",
  "correlation_id": "otel-trace-id",
  "old_value": { "status": "IN_ADJUDICATION", "adjudicated_amount": null },
  "new_value": { "status": "ADJUDICATED", "adjudicated_amount": "1500.0000" },
  "changed_fields": ["status", "adjudicated_amount"],
  "metadata": {
    "ai_assisted": true,
    "ai_confidence": 0.87,
    "override": false
  }
}
```

### Entity identity on audit events — `entityName` must be a friendly string

The `AuditEvent.create(...)` factory takes both `entityId` (UUID for the join back to the source table) AND `entityName` (the human-readable identifier the audit list will actually display: scheme name, claim number, payment reference, etc.). Both are required — never pass the UUID at the entityName slot.

**Why:** Audit listings live in the operational UI and are read by tenant admins and operations staff who don't have the source tables open. An audit log that shows "Scheme — `a7f3…d419` — CREATE" forces every viewer to run a DB lookup just to know what was created. The earlier 10-arg `AuditEvent.create(...)` overload silently defaulted `entityName = entityId`, which meant every caller stored the UUID twice — the bug was caught and the overload removed in a 2026-06 sweep.

**Per-entity friendly name conventions (extend as new entities land):**

| Entity              | entityName field used                  |
|---------------------|----------------------------------------|
| Member              | `getMemberNumber()`                     |
| Group               | `getName()`                             |
| Dependant           | `getFirstName() + " " + getLastName()`  |
| Scheme              | `getName()`                             |
| SchemeBenefit       | `getName()`                             |
| AgeGroup            | `getName()`                             |
| Contribution        | composed: `"member <id> <periodStart>..<periodEnd>"` (no number field on entity yet) |
| Invoice             | `getInvoiceNumber()`                    |
| Claim               | `getClaimNumber()`                      |
| Adjustment          | `getAdjustmentNumber()`                 |
| Payment             | `getPaymentNumber()`                    |
| PaymentRun          | `getRunNumber()`                        |
| PaymentAdvice       | `adviceNumber()`                        |
| BankReconciliation  | `getReferenceNumber()`                  |
| AdvancePayment      | `getReference()` (user-supplied)        |
| CtcPayment          | composed: `"CTC <amount> <currency>"` (no number field on entity yet) |
| TenantBankAccount   | `getLabel()`                            |
| DebitNote/CreditNote| `getReference()`                        |
| Transaction         | `getTransactionNumber()`                |

When you add a new audit-emitting entity, give it a stable human-readable identifier (number, reference, or name) and reference that field from `publishAudit`. If the entity genuinely has no such field, compose one from descriptive columns (see Contribution/CtcPayment) — never fall back to a UUID.

### Actor identity — REQUIRED on every audit event

Every audit event must carry **both** `actorId` (UUID for the join back to the users table) **and** `actorEmail` (human-readable identifier — email, falling back to `preferred_username`). Audit emissions that ship only the UUID force every viewer to run a DB join to see who did what; that defeats the point of an audit feed.

**Java services** — use the shared `AuditActor` helper, never extract claims inline:

```java
// Controller
@PostMapping
public Mono<MemberResponse> enroll(@Valid @RequestBody CreateMemberRequest req,
                                   @AuthenticationPrincipal Jwt jwt) {
    return memberService
        .enroll(req, AuditActor.id(jwt), AuditActor.email(jwt))
        .map(MemberResponse::from);
}

// Service signature
public Mono<Member> enroll(CreateMemberRequest req, String actorId, String actorEmail) { ... }

// AuditEvent emission
var event = AuditEvent.create(
    tenantId, "Member", saved.getId().toString(), saved.getMemberNumber(),
    "CREATE", actorId, actorEmail,   // <-- both, never null
    null, newValue, changedFields, correlationId);
```

**System-initiated audit events** (scheduled jobs, Kafka consumers, internal flows with no JWT) must use `AuditActor.SYSTEM_ID` + `AuditActor.SYSTEM_EMAIL` so the source is still distinguishable. Never pass `null` for actorEmail — it's a non-null contract.

**What NOT to do:**
- ❌ `@AuthenticationPrincipal Principal principal` + `principal.getName()` only — that's the UUID alone.
- ❌ Inline `jwt.getClaimAsString("email")` per controller — duplicates the fallback logic and drifts.
- ❌ `AuditEvent.create(..., actorId, null, ...)` — passes null email, defeats human readability.
- ❌ A separate `*ActorEmail` field on the entity — the audit_event stores it, not the row.

### Required Audit Fields Per Entity

Every database table that holds business data must include these columns:

```sql
-- On every entity table
created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
created_by    UUID NOT NULL,          -- User who created the record
updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
updated_by    UUID NOT NULL,          -- User who last modified the record
```

These fields are set automatically — never by the client. The service resolves the user from the JWT/security context.

### What Must Be Audited

#### Entity Audit Events (Data Changes)

Every CREATE, UPDATE, and DELETE on business entities must emit an audit event:

| Action | What to Capture |
|--------|----------------|
| **CREATE** | Full new entity state, who created it, from which service |
| **UPDATE** | Old value AND new value of every changed field, who changed it |
| **DELETE** | Full entity state before deletion, who deleted it (soft delete: capture the `deleted_at` change) |
| **STATUS CHANGE** | Old status → new status (state machine transitions are especially important) |
| **BULK OPERATIONS** | One audit event per entity affected (not one event for the batch) |

**Critical entities that MUST have audit logging:**

| Service | Entities |
|---------|----------|
| Claims | Claims, ClaimDetails, Adjudications, PreAuthorizations, Tariffs |
| Contributions | Schemes, SchemeBenefits, Contributions, Transactions, Balances, Invoices |
| Finance | Payments, PaymentRuns, Adjustments, DebitNotes, CreditNotes, ProviderBalances |
| Users | Members, Dependants, Providers, Groups, Roles, Permissions |
| Tenancy | Tenants, TenantConfigs, Plans, FeatureFlags |
| Rules | BusinessRules (creation, modification, activation, deactivation) |

#### Security Audit Events (Auth & Access)

All authentication and authorization events must be logged, regardless of success or failure:

| Event | Details Captured |
|-------|-----------------|
| **LOGIN_SUCCESS** | user_id, username, IP, user_agent, auth_method (password, SSO, MFA), tenant_id |
| **LOGIN_FAILED** | username attempted, IP, user_agent, failure_reason (bad password, locked, MFA failed), tenant_id |
| **LOGOUT** | user_id, session_id, IP, logout_type (manual, timeout, forced) |
| **TOKEN_REFRESH** | user_id, session_id, IP |
| **TOKEN_REVOKED** | user_id, revoked_by (self or admin), reason |
| **MFA_ENABLED** | user_id, mfa_method (TOTP, SMS) |
| **MFA_DISABLED** | user_id, disabled_by |
| **PASSWORD_CHANGED** | user_id, changed_by (self or admin) — NEVER log the password |
| **PASSWORD_RESET_REQUESTED** | email/username, IP |
| **PASSWORD_RESET_COMPLETED** | user_id, IP |
| **ACCOUNT_LOCKED** | user_id, reason (too many failed attempts, admin action) |
| **ACCOUNT_UNLOCKED** | user_id, unlocked_by |
| **ROLE_ASSIGNED** | user_id, role, assigned_by |
| **ROLE_REVOKED** | user_id, role, revoked_by |
| **PERMISSION_DENIED** | user_id, attempted_action, resource, IP |
| **IMPERSONATION_START** | super_admin_id, target_tenant_id, target_user_id |
| **IMPERSONATION_END** | super_admin_id, duration |
| **API_KEY_CREATED** | user_id, key_name (never the key itself) |
| **API_KEY_REVOKED** | user_id, key_name, revoked_by |

**Source**: Security events come from two places:
1. **Keycloak event listener** — Login, logout, MFA, password events. Configure a custom SPI or use Keycloak's built-in event listener to publish to Kafka (`medfund.security.*` topics).
2. **Service-level guards** — Permission denied, impersonation events. Emitted by API Gateway and individual services.

### Per-Language Implementation

#### Java (Spring Boot WebFlux) — Reactive Audit via Service Layer + Reactor Kafka

```java
// Base auditable entity — ALL R2DBC entities extend this
public abstract class AuditableEntity {

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;
}

// Reactive auditor — resolves current user from reactive SecurityContext
@Component
public class ReactiveAuditAware implements ReactiveAuditorAware<UUID> {
    @Override
    public Mono<UUID> getCurrentAuditor() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .map(auth -> ((JwtUser) auth.getPrincipal()).getUserId());
    }
}

// R2DBC entity callback — auto-populate audit fields before save
@Component
public class AuditingEntityCallback implements BeforeConvertCallback<AuditableEntity> {
    private final ReactiveAuditAware auditAware;

    @Override
    public Publisher<AuditableEntity> onBeforeConvert(AuditableEntity entity, SqlIdentifier table) {
        return auditAware.getCurrentAuditor().map(userId -> {
            entity.setUpdatedAt(Instant.now());
            entity.setUpdatedBy(userId);
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(Instant.now());
                entity.setCreatedBy(userId);
            }
            return entity;
        });
    }
}

// Reactive audit event publisher — wraps service operations
@Component
public class AuditPublisher {
    private final KafkaSender<String, AuditEvent> kafkaSender;

    /**
     * Wraps a reactive save operation with audit event publishing.
     * Usage: auditPublisher.audited("CREATE", "claim", null, claimRepository.save(entity))
     */
    public <T> Mono<T> audited(String action, String entityType,
                                 @Nullable T oldValue, Mono<T> operation) {
        return operation.flatMap(newValue ->
            buildAuditEvent(action, entityType, oldValue, newValue)
                .flatMap(event -> kafkaSender.send(
                    Mono.just(SenderRecord.create(
                        new ProducerRecord<>("medfund.audit.entity-changed",
                            event.getEntityId().toString(), event), null
                    ))
                ).then(Mono.just(newValue)))
        );
    }

    private <T> Mono<AuditEvent> buildAuditEvent(String action, String entityType,
                                                    T oldValue, T newValue) {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> AuditEvent.builder()
                .eventId(UuidV7.generate())
                .tenantId(TenantContext.getCurrentTenant())
                .actor(extractActor(ctx))
                .action(action)
                .entityType(entityType)
                .entityId(extractId(newValue != null ? newValue : oldValue))
                .service(serviceName)
                .timestamp(Instant.now())
                .correlationId(MDC.get("traceId"))
                .oldValue(oldValue)
                .newValue(newValue)
                .changedFields(computeChangedFields(oldValue, newValue))
                .build());
    }
}

// Usage in service layer
@Service
public class ClaimService {
    private final AuditPublisher auditPublisher;
    private final ClaimRepository claimRepository;

    public Mono<Claim> submit(ClaimSubmissionRequest request) {
        Claim entity = toEntity(request);
        return auditPublisher.audited("CREATE", "claim", null,
            claimRepository.save(entity)
        );
    }

    public Mono<Claim> adjudicate(UUID claimId, AdjudicationRequest request) {
        return claimRepository.findById(claimId)
            .flatMap(existing -> {
                Claim old = existing.snapshot(); // capture before state
                existing.setStatus("ADJUDICATED");
                existing.setAdjudicatedAmount(request.amount());
                return auditPublisher.audited("UPDATE", "claim", old,
                    claimRepository.save(existing)
                );
            });
    }
}
```

#### Go (Fiber) — Middleware + Repository Pattern

```go
// Audit middleware wraps handlers that mutate data
func AuditMiddleware(entityType string, action string) fiber.Handler {
    return func(c *fiber.Ctx) error {
        // Capture before-state for updates
        var oldValue interface{}
        if action == "UPDATE" || action == "DELETE" {
            oldValue = getEntityBeforeState(c, entityType)
        }

        err := c.Next() // Execute the handler

        if err == nil && c.Response().StatusCode() < 400 {
            event := AuditEvent{
                EventID:       uuidv7.New(),
                TenantID:      c.Locals("tenant_id").(string),
                Actor:         extractActor(c),
                Action:        action,
                EntityType:    entityType,
                EntityID:      c.Params("id"),
                Service:       "notification-service",
                Timestamp:     time.Now().UTC(),
                CorrelationID: c.Get("X-Trace-ID"),
                OldValue:      oldValue,
                NewValue:      c.Locals("response_body"),
            }
            publishToKafka("medfund.audit.entity-changed", event)
        }
        return err
    }
}

// Usage in routes
app.Post("/api/v2/notifications",
    AuditMiddleware("notification", "CREATE"),
    handler.CreateNotification,
)
```

#### Python (FastAPI) — Decorator Pattern

```python
from functools import wraps

def audited(entity_type: str, action: str):
    def decorator(func):
        @wraps(func)
        async def wrapper(*args, **kwargs):
            # Capture old state for updates
            old_value = None
            if action in ("UPDATE", "DELETE"):
                old_value = await get_entity_state(kwargs)

            result = await func(*args, **kwargs)

            # Publish audit event
            event = AuditEvent(
                event_id=uuid7(),
                tenant_id=get_current_tenant(),
                actor=get_current_actor(),
                action=action,
                entity_type=entity_type,
                entity_id=str(result.id) if hasattr(result, "id") else None,
                service="ai-service",
                timestamp=datetime.now(UTC),
                correlation_id=get_trace_id(),
                old_value=old_value,
                new_value=result.model_dump() if result else None,
            )
            await publish_audit_event(event)
            return result
        return wrapper
    return decorator

# Usage
@router.post("/predictions/adjudication")
@audited(entity_type="ai_prediction", action="CREATE")
async def predict_adjudication(request: AdjudicationRequest) -> AdjudicationPrediction:
    ...
```

#### Elixir (Phoenix) — Ecto Multi + PubSub

```elixir
defmodule InsureFlow.Audit do
  @doc "Wraps an Ecto.Multi with audit event publishing"
  def audited_update(multi, name, changeset, actor) do
    multi
    |> Ecto.Multi.update(name, changeset)
    |> Ecto.Multi.run(:"audit_#{name}", fn _repo, changes ->
      entity = Map.get(changes, name)
      publish_audit_event(%{
        action: "UPDATE",
        entity_type: entity.__struct__ |> to_string() |> String.split(".") |> List.last() |> String.downcase(),
        entity_id: entity.id,
        actor: actor,
        old_value: changeset.data |> Map.from_struct(),
        new_value: entity |> Map.from_struct(),
        changed_fields: Map.keys(changeset.changes)
      })
      {:ok, :audited}
    end)
  end
end
```

### Security Event Pipeline

```
Keycloak ──(event listener SPI)──▶ Kafka: medfund.security.auth-events
                                          │
API Gateway ──(middleware)──────────▶ Kafka: medfund.security.access-events
                                          │
Services ──(guards/interceptors)───▶ Kafka: medfund.security.access-events
                                          │
                                          ▼
                                   Audit Service (Go)
                                          │
                                   ├── Store in per-tenant audit_events table
                                   ├── Store security events in security_events table
                                   ├── Real-time alerts (suspicious patterns)
                                   └── Feed to Grafana dashboards
```

### Security Event Database Schema

```sql
-- Security events table (per-tenant schema)
CREATE TABLE security_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,     -- 'LOGIN_SUCCESS', 'LOGIN_FAILED', 'PERMISSION_DENIED', etc.
    user_id         UUID,                      -- NULL for failed logins with unknown user
    username        VARCHAR(255),              -- Username attempted (even if user doesn't exist)
    ip_address      INET NOT NULL,
    user_agent      TEXT,
    session_id      VARCHAR(255),
    details         JSONB NOT NULL DEFAULT '{}', -- Event-specific details
    success         BOOLEAN NOT NULL,
    failure_reason  VARCHAR(255),              -- NULL if success=true
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    INDEX idx_security_events_user (user_id, created_at DESC),
    INDEX idx_security_events_type (event_type, created_at DESC),
    INDEX idx_security_events_ip (ip_address, created_at DESC),
    INDEX idx_security_events_failed (success, created_at DESC) WHERE success = FALSE
) PARTITION BY RANGE (created_at);

-- Partition monthly for performance
CREATE TABLE security_events_2026_03 PARTITION OF security_events
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
```

### Security Monitoring & Alerts

The Audit Service (Go) implements real-time pattern detection:

| Pattern | Trigger | Action |
|---------|---------|--------|
| **Brute force** | >5 failed logins from same IP in 5 minutes | Block IP temporarily, alert admin |
| **Account enumeration** | >10 failed logins with different usernames from same IP | Block IP, alert security |
| **Impossible travel** | Login from geographically distant location within short time | Flag session, notify user |
| **Privilege escalation** | Role change to admin outside of normal workflow | Alert super admin immediately |
| **Off-hours access** | Admin login outside business hours (per tenant timezone) | Flag for review |
| **Mass data access** | Unusual volume of record reads (export-like pattern) | Alert tenant admin |
| **Impersonation abuse** | Impersonation session exceeds 30 minutes | Auto-terminate, alert |

### Audit Log Immutability

- Audit events are **append-only**. No UPDATE or DELETE is ever allowed on `audit_events` or `security_events` tables.
- Database-level protection:

```sql
-- Prevent any modification to audit tables
CREATE RULE audit_no_update AS ON UPDATE TO audit_events DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_events DO INSTEAD NOTHING;
CREATE RULE security_no_update AS ON UPDATE TO security_events DO INSTEAD NOTHING;
CREATE RULE security_no_delete AS ON DELETE TO security_events DO INSTEAD NOTHING;
```

- Retention: minimum **7 years** for healthcare compliance. Older partitions are moved to cold storage (S3 Glacier) but remain queryable.

### Audit UI (Angular — Tenant Admin + Super Admin)

| Feature | Tenant Admin | Super Admin |
|---------|-------------|-------------|
| View entity audit trail | Own tenant only | Any tenant |
| View security events | Own tenant only | Cross-tenant |
| Filter by user, action, entity, date | Yes | Yes |
| Export audit log (CSV/JSON) | Yes | Yes |
| Real-time security alerts | Own tenant | Platform-wide |
| Suspicious activity dashboard | Own tenant | Platform-wide |
| Audit log retention settings | View only | Configure |

## Database Conventions

- **Table names**: `snake_case`, plural (`claims`, `payment_runs`)
- **Column names**: `snake_case` (`created_at`, `currency_code`)
- **Primary keys**: UUID v7 (`gen_random_uuid()` or app-generated)
- **Foreign keys**: Named `{referenced_table_singular}_id` (e.g., `member_id`, `claim_id`)
- **Timestamps**: `TIMESTAMPTZ` always (never `TIMESTAMP`). Stored in UTC
- **Monetary amounts**: `DECIMAL(19,4)` paired with `CHAR(3)` currency code
- **Enums**: PostgreSQL `CREATE TYPE` for status fields. Never string columns for bounded sets
- **Indexes**: Named `idx_{table}_{columns}`. Always index foreign keys
- **Soft delete**: `deleted_at TIMESTAMPTZ` (NULL = active). Filtered by default in queries
- **Audit fields**: Every table has `created_at`, `updated_at`, `created_by`, `updated_by`

## API Conventions

- **Versioning**: URL path (`/api/v2/`)
- **Pagination**: Cursor-based by default. Offset for admin/reporting
- **Errors**: RFC 9457 Problem Details format
- **Naming**: Kebab-case URLs (`/payment-runs/`), camelCase JSON fields
- **HTTP methods**: GET (read), POST (create), PATCH (partial update), DELETE (soft delete)
- **Status codes**: 200 (OK), 201 (created), 204 (no content), 400 (validation), 401 (unauth), 403 (forbidden), 404 (not found), 409 (conflict), 422 (unprocessable), 500 (server error)

## Git Conventions

- **Branch naming**: `feature/{ticket-id}-short-description`, `fix/{ticket-id}-short-description`, `chore/description`
- **Commit messages**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`)
- **PR size**: Small, focused PRs. Max ~400 lines changed. Split larger work into stacked PRs
- **Reviews**: Required from at least one team member. CI must pass
- **Protected branches**: `main` (production), `staging` (staging). No direct pushes
