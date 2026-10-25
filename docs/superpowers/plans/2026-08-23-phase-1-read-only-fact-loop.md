# Phase 1 Read-Only Fact Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a traceable read-only flow in which a Core `REVIEWER` creates an analysis, Copilot captures an immutable sanitized Core snapshot, reliably dispatches work through Outbox/RabbitMQ, and returns a validated deterministic Stub result without calling any Core write API.

**Architecture:** FinGuard Core adds two authenticated read contracts and remains the source of business truth. FinGuard Copilot forwards the JWT only during the create request, persists `analysis_run + idempotency + snapshot + outbox` in one MySQL transaction, discards the JWT, then processes the immutable snapshot through an idempotent RabbitMQ worker. The Phase 1 Stub returns only `INSUFFICIENT_EVIDENCE`; a successful directional recommendation remains blocked until Phase 2 supplies valid policy citations.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring MVC `RestClient`, Spring JDBC, Flyway, MySQL 8.4.10, Spring AMQP, RabbitMQ, Testcontainers, JUnit 5, AssertJ, MockMvc, Jackson

**Spec:** `docs/SCOPE.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/adr/0006-capture-context-before-async-processing.md`, and `docs/adr/0008-durable-agent-dispatch-and-checkpoint.md`

## Global Constraints

- Core owns deterministic facts and final review decisions; Copilot is read-only and must never call `PATCH /api/review-tasks/{id}/decision` or any other Core write endpoint.
- Only a Core user with role `REVIEWER` may create or view an analysis.
- The JWT may exist only in request-local memory while calling Core; it must not enter MySQL, RabbitMQ, logs, exception messages, metrics, snapshots, or model input.
- Return `202 Accepted` only after identity verification, context capture, and one committed MySQL transaction containing the run, immutable snapshot, idempotency reservation, and Outbox event.
- The same reviewer, idempotency key, and request hash returns the original analysis for 24 hours; the same key with a different request hash returns `409 Conflict`.
- Snapshots are immutable, SHA-256 checksummed, and expire after 30 days. Retention cleanup may remove snapshot JSON but must retain the checksum and mark the run as no longer fully reproducible.
- RabbitMQ delivery is at least once. Duplicate messages, publisher retries, worker restarts, and out-of-order deliveries must not execute one analysis more than once or regress its state.
- Phase 1 uses only deterministic Stub analysis. No real model, RAG, Qdrant, Redis, Graph, Tool Calling, MCP, SSE, or feedback implementation belongs in this plan.
- Ordinary tests remain independent of model keys and paid APIs. Container tests may use isolated MySQL and RabbitMQ Testcontainers.
- Do not edit Copilot Flyway `V1__create_analysis_run.sql` or any applied Core migration; add a new migration in each repository when schema changes are required.
- Do not commit or push either repository unless the user explicitly authorizes it. Commit commands below are checkpoints, not authorization.

## Approach Decision

Three sequences were considered:

1. Build a synchronous Core-to-Stub demo first and add reliability later. This is fast, but it creates a temporary API contract that cannot honestly guarantee accepted work survives a process or broker failure.
2. Add all Phase 1 tables, APIs, RabbitMQ code, and guardrails in one cross-repository change. This matches the final topology, but makes contract, security, transaction, and messaging regressions difficult to isolate.
3. Deliver contract -> atomic acceptance -> deterministic output -> reliable dispatch -> failure acceptance. This is the selected sequence because each boundary is independently testable while still converging on the accepted architecture without throwaway production code.

---

## Planned File Map

### FinGuard Core (`C:\dev\finguard-core`)

- `src/main/java/com/finguard/core/auth/controller/AuthController.java` — expose the authenticated identity read endpoint.
- `src/main/java/com/finguard/core/auth/vo/CurrentUserResponse.java` — stable identity response without password or token data.
- `src/main/java/com/finguard/core/review/controller/ReviewTaskController.java` — expose the context endpoint.
- `src/main/java/com/finguard/core/review/service/ReviewTaskService.java` — context query boundary.
- `src/main/java/com/finguard/core/review/service/impl/ReviewTaskServiceImpl.java` — read-only context assembly.
- `src/main/java/com/finguard/core/review/mapper/ReviewTaskMapper.java` — one joined context query.
- `src/main/java/com/finguard/core/review/model/ReviewTaskContextView.java` — internal flat query projection.
- `src/main/java/com/finguard/core/review/vo/ReviewTaskContextResponse.java` — minimal sanitized public contract.
- `src/main/java/com/finguard/core/auth/config/SecurityConfiguration.java` — explicit role rules for the two contracts.
- `src/test/java/com/finguard/core/auth/AuthCurrentUserHttpIntegrationTest.java` — `200/401` identity contract.
- `src/test/java/com/finguard/core/review/ReviewTaskContextHttpIntegrationTest.java` — `200/401/403/404`, shape, and no-write contract.
- `src/test/java/com/finguard/core/OpenApiHttpIntegrationTest.java` — OpenAPI path assertions.

### FinGuard Copilot (`C:\dev\finguard-copilot`)

- `src/main/java/com/finguard/copilot/coreclient/port/*` — corrected framework-free Core contracts and typed upstream failures.
- `src/main/java/com/finguard/copilot/coreclient/http/*` — fixed-path `RestClient` adapter and DTO mapping.
- `src/main/java/com/finguard/copilot/analysis/domain/*` — state, recommendation, result, and transition rules.
- `src/main/java/com/finguard/copilot/analysis/application/*` — create/query use cases and deterministic orchestration.
- `src/main/java/com/finguard/copilot/analysis/persistence/*` — JDBC repositories and transaction implementation.
- `src/main/java/com/finguard/copilot/api/analysis/*` — create/query HTTP DTOs and controller.
- `src/main/java/com/finguard/copilot/context/*` — canonical snapshot serialization, checksum, and retention.
- `src/main/java/com/finguard/copilot/dispatch/*` — Outbox relay, AMQP topology, message contract, and idempotent worker.
- `src/main/java/com/finguard/copilot/guardrail/*` — JSON-shape and fact-consistency validation.
- `src/main/resources/db/migration/V2__create_phase1_fact_loop_tables.sql` — additive Phase 1 persistence.
- `src/main/resources/schema/analysis-output-v1.schema.json` — versioned result shape.
- `src/test/java/com/finguard/copilot/**/*` — focused unit, MockMvc, MySQL, RabbitMQ, timeout, and failure-recovery tests.

---

### Task 1: Reconcile the Phase 0 Core context port with real Core invariants

**Files:**
- Modify: `src/main/java/com/finguard/copilot/coreclient/port/ReviewTaskContext.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/port/CoreClientException.java`
- Modify: `src/test/java/com/finguard/copilot/port/PortContractTest.java`

**Interfaces:**
- Produces: `ReviewTaskContext.ReviewTask.version >= 0`.
- Produces: nullable `ReviewTaskContext.ReviewTask.ruleCode` for `RECONCILIATION_EXCEPTION`; nonblank when present.
- Produces: `CoreClientException.Kind` values `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `UNAVAILABLE`, and `INVALID_RESPONSE`.

- [ ] **Step 1: Add regression tests for real Core values**

Add tests constructing a review task with version `0` and a reconciliation exception with `ruleCode == null`. Keep rejection tests for a negative version and blank non-null rule code.

- [ ] **Step 2: Run the port test and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=PortContractTest test
```

Expected: the version-zero and nullable-rule tests fail against the Phase 0 contract.

- [ ] **Step 3: Apply the minimal contract correction**

Use this validation rule in `ReviewTaskContext.ReviewTask`:

```java
if (version < 0) {
    throw new IllegalArgumentException("version must not be negative");
}
if (ruleCode != null && ruleCode.isBlank()) {
    throw new IllegalArgumentException("ruleCode must be null or non-blank");
}
```

Add a framework-free exception:

```java
public final class CoreClientException extends RuntimeException {
    public enum Kind {
        UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, UNAVAILABLE, INVALID_RESPONSE
    }

    private final Kind kind;

    public CoreClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
```

Never include a bearer token or upstream response body in the exception message.

- [ ] **Step 4: Verify the framework-free boundary**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=PortContractTest test
rg -n "org\.springframework|jakarta|com\.fasterxml" src/main/java/com/finguard/copilot/coreclient/port
```

Expected: tests pass and `rg` returns no framework imports.

### Task 2: Add the Core current-user contract

**Files:**
- Create: `C:\dev\finguard-core\src\main\java\com\finguard\core\auth\vo\CurrentUserResponse.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\auth\controller\AuthController.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\auth\config\SecurityConfiguration.java`
- Create: `C:\dev\finguard-core\src\test\java\com\finguard\core\auth\AuthCurrentUserHttpIntegrationTest.java`
- Modify: `C:\dev\finguard-core\src\test\java\com\finguard\core\OpenApiHttpIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/auth/me -> CurrentUserResponse(String username, List<RoleCode> roles)`.
- Security: any authenticated Core user may inspect their identity; Copilot independently requires `REVIEWER`.

- [ ] **Step 1: Write the identity HTTP contract tests**

Assert that a valid reviewer JWT returns normalized username and sorted distinct roles, a valid admin JWT returns only `ADMIN`, and a missing/invalid JWT returns the existing `401` error contract. Assert the response has no token, password, password hash, or signing data.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=AuthCurrentUserHttpIntegrationTest test
```

Expected: `404` because `/api/auth/me` does not exist.

- [ ] **Step 3: Implement the response and controller method**

Use the authenticated JWT claims already issued by Core:

```java
public record CurrentUserResponse(
        String username,
        List<RoleCode> roles
) {}

@GetMapping("/me")
@Operation(summary = "Get the authenticated user identity")
public CurrentUserResponse me(
        @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
    String username = jwt.getClaimAsString("username");
    List<RoleCode> roles = jwt.getClaimAsStringList("roles").stream()
            .map(RoleCode::valueOf)
            .distinct()
            .sorted(java.util.Comparator.comparing(Enum::name))
            .toList();
    return new CurrentUserResponse(username, roles);
}
```

Validate that `username` is nonblank; map an impossible authenticated-token shape to the existing stable server error without echoing claims.

- [ ] **Step 4: Add explicit authorization and OpenAPI assertions**

Add `GET /api/auth/me` to authenticated routes in `SecurityConfiguration`; do not make it anonymous. Assert `/api/auth/me` exists in OpenAPI and has bearer security.

- [ ] **Step 5: Run focused and full Core tests**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=AuthCurrentUserHttpIntegrationTest,OpenApiHttpIntegrationTest,RbacAuthorizationIntegrationTest" test
.\mvnw.cmd -B -ntp test
```

Expected: all selected and full Core tests pass.

### Task 3: Add the Core review-task context contract

**Files:**
- Create: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\model\ReviewTaskContextView.java`
- Create: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\vo\ReviewTaskContextResponse.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\mapper\ReviewTaskMapper.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\service\ReviewTaskService.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\service\impl\ReviewTaskServiceImpl.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\review\controller\ReviewTaskController.java`
- Modify: `C:\dev\finguard-core\src\main\java\com\finguard\core\auth\config\SecurityConfiguration.java`
- Create: `C:\dev\finguard-core\src\test\java\com\finguard\core\review\ReviewTaskContextHttpIntegrationTest.java`
- Modify: `C:\dev\finguard-core\src\test\java\com\finguard\core\OpenApiHttpIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/review-tasks/{reviewTaskId}/context` with the exact nested names used by Copilot `ReviewTaskContext`.
- Security: `REVIEWER` only; `ADMIN` receives `403`.
- Data: account name/status is allowed; account number, descriptions, user data, CSV bytes, and credentials are excluded.

- [ ] **Step 1: Write context shape and authorization tests**

Cover one `RISK_HIT` and one `RECONCILIATION_EXCEPTION`. Assert `version == 0` for a pending task, `ruleCode == null` for a reconciliation exception, risk facts are source-labelled, timestamps are ISO-8601 with an offset, and amounts serialize as decimal numbers without binary rounding. Cover missing JWT `401`, admin-only JWT `403`, unknown task `404`, and invalid ID `400`.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=ReviewTaskContextHttpIntegrationTest test
```

Expected: the context path is absent.

- [ ] **Step 3: Define the minimal response contract**

Create nested records matching this public shape:

```java
public record ReviewTaskContextResponse(
        ReviewTask reviewTask,
        Transaction transaction,
        Reconciliation reconciliation,
        List<RiskFact> riskFacts,
        AccountSummary accountSummary,
        Instant capturedAt
) {
    public record ReviewTask(long id, long version, String status,
            String ruleCode, String reasonCode, Instant createdAt) {}
    public record Transaction(long id, long accountId, BigDecimal amount,
            String currency, Instant occurredAt, String sourceType) {}
    public record Reconciliation(String resultType, String matchMethod,
            String reasonCode) {}
    public record RiskFact(String code, String value, String source) {}
    public record AccountSummary(String status, String displayName) {}
}
```

Convert Core `LocalDateTime` values to UTC `Instant` using one documented application time-zone rule.

- [ ] **Step 4: Implement one read-only joined query**

Join `review_tasks` to the direct or risk-linked `reconciliation_results`, `risk_hits`, `transactions`, and `accounts`. Select only fields required by the response. For a risk hit, emit non-null observed/threshold/window values as individual `RiskFact` entries with sources such as `riskHit.observedAmount`; omit null facts. For a reconciliation exception, return an empty immutable risk fact list.

- [ ] **Step 5: Wire service and controller**

Add:

```java
ReviewTaskContextResponse getContext(Long reviewTaskId);
```

and:

```java
@GetMapping("/{reviewTaskId}/context")
public ReviewTaskContextResponse getContext(
        @PathVariable @Positive Long reviewTaskId) {
    return reviewTaskService.getContext(reviewTaskId);
}
```

Mark the service method `@Transactional(readOnly = true)` and reuse `ReviewTaskNotFoundException`.

- [ ] **Step 6: Restrict the context path and verify no writes**

Add an explicit `REVIEWER`-only matcher before the broader `/api/review-tasks/*` rule. In the integration test, snapshot counts and versions for `review_tasks`, `transactions`, `accounts`, `reconciliation_results`, and `risk_hits` before and after the GET; assert no values changed.

- [ ] **Step 7: Run the full Core suite**

Run:

```powershell
.\mvnw.cmd -B -ntp test
git diff --check
```

Expected: full Core tests pass and no whitespace errors exist.

### Task 4: Implement the Copilot fixed-path Core HTTP adapter

**Files:**
- Modify: `src/main/java/com/finguard/copilot/config/CopilotProperties.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/http/CoreHttpConfiguration.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/http/HttpCoreClient.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/http/CoreCurrentUserResponse.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/http/CoreReviewTaskContextResponse.java`
- Test: `src/test/java/com/finguard/copilot/coreclient/http/HttpCoreClientTest.java`
- Test: `src/test/java/com/finguard/copilot/coreclient/http/HttpCoreClientTimeoutTest.java`

**Interfaces:**
- Consumes: `CoreClient.currentUser(String bearerToken)` and `reviewTaskContext(long, String)`.
- Produces: only `GET /api/auth/me` and `GET /api/review-tasks/{id}/context` calls.
- Configuration: connect timeout `2s`, read timeout `3s`, base URL from `FINGUARD_CORE_BASE_URL`.

- [ ] **Step 1: Write adapter mapping tests**

Use `MockRestServiceServer` with a `RestClient.Builder`. Verify the exact method/path and `Authorization: Bearer ...` header, map valid responses to immutable port records, and reject unknown/missing fields as `INVALID_RESPONSE`.

- [ ] **Step 2: Write upstream error tests**

Assert `401 -> UNAUTHENTICATED`, `403 -> FORBIDDEN`, `404 -> NOT_FOUND`, `429/5xx/I/O/timeout -> UNAVAILABLE`. Assert exception text and captured logs do not contain the bearer token or response body.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=HttpCoreClientTest,HttpCoreClientTimeoutTest" test
```

Expected: adapter classes are absent.

- [ ] **Step 4: Implement fixed endpoints and strict response mapping**

Construct paths inside the adapter; never accept a URL or path from a caller. Use `RestClient` and pass the token only through the request header. Validate the mapped DTO by constructing the existing port records.

- [ ] **Step 5: Add timeout configuration and test with a delayed local server**

Extend `CopilotProperties.Core` to include `connectTimeout` and `readTimeout` durations with positive validation and defaults `2s`/`3s`. In the timeout test, use JDK `HttpServer` bound to loopback, delay beyond the configured test read timeout, and assert `CoreClientException.Kind.UNAVAILABLE`.

- [ ] **Step 6: Verify adapter and path whitelist**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=HttpCoreClientTest,HttpCoreClientTimeoutTest,CopilotPropertiesTest" test
rg -n "patch\(|post\(|put\(|delete\(" src/main/java/com/finguard/copilot/coreclient
```

Expected: tests pass and no write-method invocation is present.

### Task 5: Add Phase 1 run, snapshot, idempotency, result, and Outbox persistence

**Files:**
- Create: `src/main/resources/db/migration/V2__create_phase1_fact_loop_tables.sql`
- Create: `src/main/java/com/finguard/copilot/analysis/domain/AnalysisStatus.java`
- Create: `src/main/java/com/finguard/copilot/analysis/domain/AnalysisRun.java`
- Create: `src/main/java/com/finguard/copilot/analysis/domain/AnalysisRecommendation.java`
- Create: `src/main/java/com/finguard/copilot/context/FactSnapshot.java`
- Create: `src/main/java/com/finguard/copilot/context/SnapshotCodec.java`
- Create: `src/main/java/com/finguard/copilot/analysis/persistence/AnalysisRunRepository.java`
- Create: `src/main/java/com/finguard/copilot/analysis/persistence/JdbcAnalysisRunRepository.java`
- Create: `src/main/java/com/finguard/copilot/analysis/persistence/AnalysisCreationStore.java`
- Test: `src/test/java/com/finguard/copilot/Phase1MigrationTest.java`
- Test: `src/test/java/com/finguard/copilot/context/SnapshotCodecTest.java`
- Test: `src/test/java/com/finguard/copilot/analysis/persistence/AnalysisCreationStoreIntegrationTest.java`

**Interfaces:**
- Produces statuses: `CONTEXT_READY`, `QUEUED`, `RETRIEVAL_READY`, `GENERATING`, `VALIDATING`, `SUCCEEDED`, `FAILED`.
- Produces one transaction method: `CreatedAnalysis create(NewAnalysis analysis, FactSnapshot snapshot, OutboxRecord event, Instant idempotencyExpiresAt)`.

- [ ] **Step 1: Write migration assertions**

Assert V2 does not alter V1 in place, drops only `uk_analysis_run_reviewer_idempotency`, adds `failure_code` and `fully_reproducible`, and creates `analysis_idempotency_key`, `analysis_input_snapshot`, `analysis_result`, `outbox_event`, and `analysis_message_receipt` with foreign keys, checks, and indexes.

- [ ] **Step 2: Verify migration RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=Phase1MigrationTest test
```

Expected: V2 tables are missing.

- [ ] **Step 3: Create the additive V2 migration**

Use `BINARY(16)` UUIDs. Store canonical JSON in MySQL `JSON`, SHA-256 in `BINARY(32)`, timestamps as `DATETIME(6)`, and attempts as unsigned integers. Enforce:

```text
analysis_idempotency_key UNIQUE(reviewer_username, idempotency_key)
analysis_input_snapshot PRIMARY KEY(analysis_id)
analysis_result PRIMARY KEY(analysis_id)
analysis_message_receipt PRIMARY KEY(event_id)
outbox_event INDEX(published_at, next_attempt_at, created_at)
```

`analysis_input_snapshot` has `captured_at`, `expires_at`, and nullable `deleted_at`. `outbox_event` has `event_type`, `schema_version`, `payload_json`, `attempt_count`, `next_attempt_at`, `published_at`, and bounded `last_error_code`.

- [ ] **Step 4: Write canonical snapshot tests**

Assert the same port context always serializes to the same UTF-8 bytes and SHA-256 despite input collection order; assert no Authorization value can be supplied; assert decoding recreates the same immutable facts.

- [ ] **Step 5: Implement canonical snapshot encoding**

Use one configured Jackson `ObjectMapper` with deterministic property and map ordering. Define:

```java
public record FactSnapshot(
        int schemaVersion,
        ReviewTaskContext facts,
        Instant capturedAt,
        Instant expiresAt,
        byte[] sha256
) {}
```

Use schema version `1` and `expiresAt = capturedAt.plus(30, DAYS)`.

- [ ] **Step 6: Write transactional persistence tests**

With MySQL Testcontainers, assert a successful creation writes exactly one row to the run, idempotency, snapshot, and outbox tables. Force each insert to fail in turn and assert all four tables remain unchanged. Assert the same active reviewer/key cannot be inserted concurrently twice.

- [ ] **Step 7: Implement JDBC repositories and transaction boundary**

Use `NamedParameterJdbcTemplate` and `TransactionTemplate`. Insert `CONTEXT_READY` run, idempotency reservation, immutable snapshot, and `ANALYSIS_REQUESTED` outbox event in one transaction. Do not store the bearer token in any method parameter below the application service.

- [ ] **Step 8: Verify persistence tests**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=Phase1MigrationTest,SnapshotCodecTest,AnalysisCreationStoreIntegrationTest" test
```

Expected: all migration, rollback, immutability, and concurrency tests pass.

### Task 6: Implement create/query APIs, REVIEWER enforcement, and 24-hour idempotency

**Files:**
- Create: `src/main/java/com/finguard/copilot/analysis/application/CreateAnalysisCommand.java`
- Create: `src/main/java/com/finguard/copilot/analysis/application/CreateAnalysisService.java`
- Create: `src/main/java/com/finguard/copilot/analysis/application/GetAnalysisService.java`
- Create: `src/main/java/com/finguard/copilot/api/analysis/CreateAnalysisRequest.java`
- Create: `src/main/java/com/finguard/copilot/api/analysis/AnalysisAcceptedResponse.java`
- Create: `src/main/java/com/finguard/copilot/api/analysis/AnalysisResponse.java`
- Create: `src/main/java/com/finguard/copilot/api/analysis/ReviewAnalysisController.java`
- Modify: `src/main/java/com/finguard/copilot/common/error/ErrorCode.java`
- Modify: `src/main/java/com/finguard/copilot/api/error/GlobalExceptionHandler.java`
- Test: `src/test/java/com/finguard/copilot/analysis/application/CreateAnalysisServiceTest.java`
- Test: `src/test/java/com/finguard/copilot/api/analysis/ReviewAnalysisControllerTest.java`
- Test: `src/test/java/com/finguard/copilot/analysis/persistence/AnalysisIdempotencyIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/copilot/review-analyses` with `Idempotency-Key` and body `{ "reviewTaskId": 123 }`.
- Produces: `GET /api/copilot/review-analyses/{analysisId}` scoped to the creating reviewer.
- Produces: `AnalysisAcceptedResponse(UUID analysisId, AnalysisStatus status, boolean replayed)`.

- [ ] **Step 1: Write application tests for call order and authorization**

Assert the exact order `currentUser -> role check -> idempotency lookup -> reviewTaskContext -> transaction create`. An admin-only user returns `403` before context capture. Core `401/403/404/unavailable` maps to stable Copilot `401/403/404/503` and creates no local rows.

- [ ] **Step 2: Write idempotency tests**

Cover same reviewer/key/hash within 24 hours returning the original ID with `replayed=true`; same key/different task returning `409`; different reviewers using the same key independently; expired reservation allowing a new ID; and two concurrent same-key requests producing one run and one replay.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=CreateAnalysisServiceTest,ReviewAnalysisControllerTest,AnalysisIdempotencyIntegrationTest" test
```

Expected: API and application services are absent.

- [ ] **Step 4: Implement the create use case**

Normalize `Idempotency-Key` by trimming but preserve case; require 1–100 visible ASCII characters. Hash canonical `{reviewTaskId}` bytes. Require `CurrentUser.roles()` to contain exactly the capability `REVIEWER` regardless of additional roles. Capture Core context before opening the local write transaction, encode the snapshot, and create the four records atomically.

- [ ] **Step 5: Resolve insertion races deterministically**

On duplicate idempotency reservation, reload the winning row. Return it when the request hash matches and it has not expired; return `409` for a different hash while active. When the row is expired, delete it with a compare-and-delete predicate on reviewer, key, analysis ID, and expiry, then retry the insert once. Never create a second run merely because the Core context was read twice during a race.

- [ ] **Step 6: Implement reviewer-scoped query**

Call Core `/api/auth/me` with the current request token, require `REVIEWER`, then query by `(analysisId, reviewerUsername)`. Return `404` for another reviewer's ID to avoid revealing its existence. Return result JSON only for `SUCCEEDED`; return bounded `failureCode` only for `FAILED`; never return snapshot JSON.

- [ ] **Step 7: Implement HTTP behavior and error mapping**

Return `202` for new and replayed creates, `200` for GET, RFC 9457-compatible errors, and `Location: /api/copilot/review-analyses/{analysisId}`. Extract the bearer token into a request-local variable and do not attach it to DTOs or domain records.

- [ ] **Step 8: Verify focused API tests**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=CreateAnalysisServiceTest,ReviewAnalysisControllerTest,AnalysisIdempotencyIntegrationTest" test
```

Expected: all authorization, idempotency, transaction, and response-shape tests pass.

### Task 7: Implement deterministic structured Stub analysis and guardrails

**Files:**
- Create: `src/main/resources/schema/analysis-output-v1.schema.json`
- Create: `src/main/java/com/finguard/copilot/analysis/domain/AnalysisResult.java`
- Create: `src/main/java/com/finguard/copilot/analysis/application/AnalysisOrchestrator.java`
- Create: `src/main/java/com/finguard/copilot/analysis/application/DeterministicAnalysisOrchestrator.java`
- Create: `src/main/java/com/finguard/copilot/guardrail/AnalysisOutputSchemaValidator.java`
- Create: `src/main/java/com/finguard/copilot/guardrail/FactConsistencyValidator.java`
- Test: `src/test/java/com/finguard/copilot/analysis/application/DeterministicAnalysisOrchestratorTest.java`
- Test: `src/test/java/com/finguard/copilot/guardrail/AnalysisOutputSchemaValidatorTest.java`
- Test: `src/test/java/com/finguard/copilot/guardrail/FactConsistencyValidatorTest.java`

**Interfaces:**
- Produces: `AnalysisOrchestrator.analyze(UUID analysisId, FactSnapshot snapshot) -> AnalysisResult`.
- Produces: recommendation `INSUFFICIENT_EVIDENCE` only in Phase 1.

- [ ] **Step 1: Define and test the versioned output shape**

The schema requires exactly: `schemaVersion`, `analysisId`, `reviewTaskSummary`, `riskRuleExplanation`, `businessEvidence[]`, `policyCitations[]`, `recommendation`, `reasoningSummary`, `missingInformation[]`, and `limitations[]`. Disallow unknown top-level properties. `recommendation` enum is `CONFIRMED`, `IGNORED`, or `INSUFFICIENT_EVIDENCE`.

- [ ] **Step 2: Add fact-consistency test cases**

Accept only transaction ID, account ID, amount, currency, rule code, reason code, and source paths present in the snapshot. Reject a changed amount, invented rule, invented fact path, directional recommendation without a citation, and `analysisId` mismatch.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=DeterministicAnalysisOrchestratorTest,AnalysisOutputSchemaValidatorTest,FactConsistencyValidatorTest" test
```

Expected: orchestrator and validators are absent.

- [ ] **Step 4: Implement the safe deterministic result**

Build an immutable `AnalysisResult` directly from snapshot facts. Use recommendation `INSUFFICIENT_EVIDENCE`, empty policy citations, and a limitation stating that Phase 1 has no published knowledge index. Include only copied fact values and stable template text; do not treat `StubModelGateway` content as trusted JSON.

- [ ] **Step 5: Implement schema and fact validation**

Avoid a new general-purpose schema dependency in this bounded phase. Parse the candidate as Jackson `JsonNode`, reject unknown properties, and validate the exact required-property set, node types, enum values, array item shapes, and `schemaVersion == 1` in `AnalysisOutputSchemaValidator`. Load `analysis-output-v1.schema.json` once and add a test that its `required`, `properties`, and recommendation enum exactly match the validator constants so the executable validator and published schema cannot drift. Then deserialize to `AnalysisResult` and compare every business evidence claim to the snapshot. Any failure throws a bounded code: `OUTPUT_SCHEMA_INVALID` or `FACT_VALIDATION_FAILED`; raw candidate output is not persisted.

- [ ] **Step 6: Prove directional recommendations are blocked**

Add a parameterized test for `CONFIRMED` and `IGNORED` with zero citations; both must fail. `INSUFFICIENT_EVIDENCE` with zero citations must pass when all copied facts match.

- [ ] **Step 7: Verify all guardrail tests**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=DeterministicAnalysisOrchestratorTest,AnalysisOutputSchemaValidatorTest,FactConsistencyValidatorTest" test
```

Expected: deterministic output and all negative guardrail cases pass.

### Task 8: Add transactional Outbox relay, RabbitMQ topology, and idempotent worker

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/finguard/copilot/config/CopilotProperties.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/AnalysisRequestedMessage.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/RabbitTopologyConfiguration.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/OutboxRepository.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/OutboxRelay.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/AnalysisWorker.java`
- Create: `src/main/java/com/finguard/copilot/analysis/application/AnalysisStateMachine.java`
- Test: `src/test/java/com/finguard/copilot/analysis/application/AnalysisStateMachineTest.java`
- Test: `src/test/java/com/finguard/copilot/dispatch/OutboxRelayIntegrationTest.java`
- Test: `src/test/java/com/finguard/copilot/dispatch/AnalysisWorkerIntegrationTest.java`

**Interfaces:**
- Message: `AnalysisRequestedMessage(int schemaVersion, UUID eventId, UUID analysisId, Instant occurredAt)` with schema version `1`.
- Topology: durable direct exchange `finguard.copilot.analysis.v1`, queue `finguard.copilot.analysis.request.v1`, routing key `analysis.requested`.
- Retry: `finguard.copilot.analysis.retry.v1` and 30-second TTL queue `finguard.copilot.analysis.retry.30s.v1`, maximum three worker attempts.
- Dead letter: exchange `finguard.copilot.analysis.dlx.v1`, queue `finguard.copilot.analysis.dlq.v1`.

- [ ] **Step 1: Add managed AMQP dependencies**

Add `spring-boot-starter-amqp` and test-scoped `org.testcontainers:rabbitmq`; rely on the existing Spring Boot/Testcontainers dependency management and do not add a second version property.

- [ ] **Step 2: Write state transition tests**

Allow only:

```text
CONTEXT_READY -> QUEUED -> RETRIEVAL_READY -> GENERATING
GENERATING -> VALIDATING -> SUCCEEDED
CONTEXT_READY|QUEUED|RETRIEVAL_READY|GENERATING|VALIDATING -> FAILED
```

Repeated receipt of the same or an earlier transition is a no-op; a later-to-earlier transition is rejected; `SUCCEEDED` and `FAILED` are terminal.

- [ ] **Step 3: Write relay and worker integration tests**

With isolated MySQL and RabbitMQ Testcontainers, assert unpublished events are confirmed before `published_at` is set; a broker outage leaves the row unpublished with a bounded error code and future `next_attempt_at`; relay restart publishes it; duplicate delivery creates one result and one receipt; a crash-after-result simulation followed by redelivery does not rerun analysis.

- [ ] **Step 4: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=AnalysisStateMachineTest,OutboxRelayIntegrationTest,AnalysisWorkerIntegrationTest" test
```

Expected: AMQP topology, relay, and worker are absent.

- [ ] **Step 5: Implement topology and publisher confirms**

Declare all exchanges and queues durable. Configure publisher confirms and returns. The relay selects due unpublished rows in batches using `FOR UPDATE SKIP LOCKED`, publishes the versioned message, and marks `published_at` only after a positive confirm. A publish failure increments `attempt_count`, records a low-cardinality code, and applies bounded exponential backoff.

- [ ] **Step 6: Implement the idempotent worker transaction**

On delivery, load the run and immutable snapshot. If the event receipt exists or the run is terminal, acknowledge without work. Otherwise apply state transitions, call `AnalysisOrchestrator`, validate, insert one `analysis_result`, mark `SUCCEEDED`, and insert `analysis_message_receipt` in one MySQL transaction. On a deterministic guardrail failure, mark `FAILED` and acknowledge. On a retryable infrastructure failure, republish to retry with incremented attempt and acknowledge only after confirm; after attempt three, mark `FAILED` with `ANALYSIS_RETRY_EXHAUSTED` and dead-letter.

- [ ] **Step 7: Verify recovery behavior**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=AnalysisStateMachineTest,OutboxRelayIntegrationTest,AnalysisWorkerIntegrationTest" test
```

Expected: duplicate, outage/recovery, bounded retry, DLQ, and terminal-state tests pass.

### Task 9: Add retention, Outbox cleanup, and failure-safety tests

**Files:**
- Create: `src/main/java/com/finguard/copilot/context/SnapshotRetentionJob.java`
- Create: `src/main/java/com/finguard/copilot/analysis/persistence/IdempotencyCleanupJob.java`
- Create: `src/main/java/com/finguard/copilot/dispatch/OutboxCleanupJob.java`
- Modify: `src/main/java/com/finguard/copilot/config/CopilotProperties.java`
- Test: `src/test/java/com/finguard/copilot/context/SnapshotRetentionJobIntegrationTest.java`
- Test: `src/test/java/com/finguard/copilot/analysis/persistence/IdempotencyCleanupJobIntegrationTest.java`
- Test: `src/test/java/com/finguard/copilot/dispatch/OutboxCleanupJobIntegrationTest.java`
- Test: `src/test/java/com/finguard/copilot/dispatch/Phase1FailureRecoveryIntegrationTest.java`

**Interfaces:**
- Snapshot retention: delete expired `facts_json`, retain checksum, set `deleted_at`, and set `analysis_run.fully_reproducible = false`.
- Idempotency retention: delete expired reservations in batches without deleting their analysis runs.
- Outbox retention: delete published events older than 7 days in batches; never delete unpublished events.

- [ ] **Step 1: Write clock-controlled retention tests**

Insert expired and unexpired snapshots. Assert only expired JSON is removed, checksums remain, and GET never exposes deleted data. Insert expired and active idempotency reservations and assert only expired reservations are removed while all analysis runs remain. Insert published/unpublished outbox rows around the 7-day boundary and assert only eligible published rows are removed.

- [ ] **Step 2: Write failure-matrix tests**

Cover Core timeout, MySQL transaction failure, RabbitMQ unavailable then restored, duplicate message, out-of-order message, malformed message schema, and worker restart. For each case assert the exact run state, row counts, retry/DLQ outcome, and absence of JWT or snapshot JSON in logs.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=SnapshotRetentionJobIntegrationTest,IdempotencyCleanupJobIntegrationTest,OutboxCleanupJobIntegrationTest,Phase1FailureRecoveryIntegrationTest" test
```

Expected: cleanup jobs and complete failure behavior are absent.

- [ ] **Step 4: Implement batched cleanup with injected `Clock`**

Use batch size `100`, snapshot retention `30d`, idempotency retention from each row's exact `expires_at`, published Outbox retention `7d`, and scheduler fixed delay `1h`. Guard cleanup with profile/property `finguard.copilot.jobs.enabled`; tests call jobs directly with a fixed clock. Each batch commits independently and reports only low-cardinality counts.

- [ ] **Step 5: Verify cleanup and failure recovery**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=SnapshotRetentionJobIntegrationTest,IdempotencyCleanupJobIntegrationTest,OutboxCleanupJobIntegrationTest,Phase1FailureRecoveryIntegrationTest" test
```

Expected: retention boundaries and every failure-matrix assertion pass.

### Task 10: Run cross-repository acceptance and close Phase 1 documentation

**Files:**
- Modify: `README.md`
- Modify: `TASKS.md`
- Modify: `docs/ARCHITECTURE.md` only where final names differ from the plan.
- Create: `docs/acceptance/phase-1-read-only-fact-loop.md`

**Interfaces:**
- Consumes: completed Tasks 1–9 and clean test environments.
- Produces: repeatable evidence for the complete Phase 1 acceptance statement.

- [ ] **Step 1: Run both full test suites**

Run:

```powershell
Push-Location C:\dev\finguard-core
.\mvnw.cmd -B -ntp test
Pop-Location

Push-Location C:\dev\finguard-copilot
.\scripts\verify.ps1
Pop-Location
```

Expected: Core and Copilot Java tests plus Copilot Python/frontend verification all pass with no skipped acceptance tests.

- [ ] **Step 2: Run real two-service acceptance**

Start isolated Core, Copilot MySQL, RabbitMQ, and Copilot with test-only credentials. Log in as a real Core reviewer, require a successful `GET /api/auth/me`, create or locate one pending review task, then:

```text
POST analysis -> 202 CONTEXT_READY/QUEUED
poll GET       -> 200 SUCCEEDED
result         -> INSUFFICIENT_EVIDENCE with copied Core facts
repeat POST    -> same analysisId, replayed=true
changed body   -> 409
admin POST     -> 403
unknown task   -> 404 and no local run
```

Stop RabbitMQ before one create, confirm the run and unpublished Outbox row persist, restart RabbitMQ, and confirm the same run reaches `SUCCEEDED` exactly once.

- [ ] **Step 3: Prove read-only and secret boundaries**

Compare Core business-table checksums/counts before and after acceptance. Search application logs and Copilot tables for the test JWT and assert no match. Inspect captured Core requests and assert only the two approved GET paths occurred.

- [ ] **Step 4: Clean acceptance resources**

Delete only the isolated acceptance containers, networks, volumes, and synthetic rows created for this run. Verify no Testcontainers remain and both application ports are released. Do not remove normal development volumes.

- [ ] **Step 5: Record evidence and update status honestly**

Document commands, test counts, HTTP outcomes, queue outage/recovery evidence, row counts, cleanup proof, and remaining Phase 2+ gaps. Check all twelve Phase 1 items in `TASKS.md` only after their direct evidence exists. Update README to say Phase 1 is complete while RAG, real models, Agent workflow, UI/SSE, and production-like deployment remain unimplemented.

- [ ] **Step 6: Audit both worktrees**

Run:

```powershell
git -C C:\dev\finguard-core diff --check
git -C C:\dev\finguard-core status --short
git -C C:\dev\finguard-copilot diff --check
git -C C:\dev\finguard-copilot status --short
rg --hidden -n -i "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|Bearer [A-Za-z0-9_-]+\." C:\dev\finguard-core C:\dev\finguard-copilot -g '!target/**' -g '!node_modules/**' -g '!*.md'
```

Expected: no whitespace or secret findings; only planned Phase 1 files are modified.

- [ ] **Step 7: Optional commit checkpoint after explicit authorization**

If and only if the user authorizes commits, commit Core and Copilot separately so each repository remains independently buildable. Do not push unless separately authorized.

## Phase 1 Acceptance Gate

- Core `GET /api/auth/me` and `GET /api/review-tasks/{id}/context` pass `200/401/403/404` contract coverage and expose only the minimal sanitized fields.
- Copilot maps Core timeout/unavailability to `503` without creating a partial run.
- Only `REVIEWER` can create/query analysis; cross-reviewer lookup does not reveal existence.
- `202` is returned only after run, snapshot, active idempotency reservation, and Outbox event commit together.
- The 24-hour idempotency behavior works under both sequential and concurrent requests.
- JWT is absent from persistence, messages, logs, metrics, model input, and API output.
- RabbitMQ outage/recovery, duplicate delivery, out-of-order delivery, malformed messages, and worker restart do not lose or duplicate analysis.
- Stub output is deterministic, schema-valid, fact-consistent, and limited to `INSUFFICIENT_EVIDENCE` until policy citations exist.
- Snapshot 30-day retention and published Outbox 7-day cleanup are tested with an injected clock and never delete active data.
- Copilot makes no Core write request and Core business state is unchanged by end-to-end acceptance.
- Both full repository test suites, `scripts/verify.ps1`, cleanup checks, secret scans, and `git diff --check` pass.
- Documentation distinguishes Phase 1 completion from unimplemented RAG, real-model, Agent, UI/SSE, and deployment stages.

## Suggested Execution Order

1. Tasks 1–3 in Core-contract order.
2. Tasks 4–6 for the synchronous `202` acceptance boundary.
3. Task 7 for deterministic safe output.
4. Tasks 8–9 for reliable asynchronous execution and cleanup.
5. Task 10 for fresh cross-repository acceptance and documentation closure.

Do not begin Phase 2 until every Phase 1 acceptance-gate item has current evidence.
