# Phase 0 Contract Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Phase 0 with validated configuration, RFC 9457 API errors, stable domain ports, and deterministic offline adapters.

**Architecture:** Project-owned Java ports isolate model, embedding, vector retrieval, and Core facts from provider/framework adapters. Deterministic implementations are available only under the explicit `stub` profile, while HTTP errors use Spring `ProblemDetail` at the API edge and configuration is validated during context startup.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Jakarta Validation, Spring MVC `ProblemDetail`, JUnit 5, AssertJ, MockMvc, ApplicationContextRunner, SHA-256, concurrent Java collections.

**Spec:** `docs/superpowers/specs/2026-08-23-phase-0-contract-foundation-design.md`

## Global Constraints

- Do not add a Spring AI starter, provider SDK, HTTP client, Qdrant client, Redis client, RabbitMQ client, or Langfuse dependency.
- Port packages use only the Java standard library and project-owned types.
- No Phase 0 test reads a model key, contacts Core, or makes an outbound network request.
- Stub beans exist only under the explicit `stub` Spring profile.
- Stub model output is generic and must not claim a successful financial recommendation.
- Core operations remain read-only and bearer tokens are method arguments, never object state.
- Database passwords remain environment-only with no runtime default.
- Follow red-green-refactor for every behavior and run the exact failing test before implementation.
- Document commit commands but do not execute them until the user explicitly authorizes a commit.

---

## Planned File Map

```text
src/main/java/com/finguard/copilot/
  FinGuardCopilotApplication.java
  api/error/GlobalExceptionHandler.java
  common/error/ErrorCode.java
  common/error/CopilotException.java
  config/CopilotProperties.java
  coreclient/port/CoreClient.java
  coreclient/port/CurrentUser.java
  coreclient/port/ReviewTaskContext.java
  model/port/ModelGateway.java
  model/port/ModelRequest.java
  model/port/ModelResponse.java
  knowledge/port/EmbeddingGateway.java
  knowledge/port/EmbeddingVector.java
  knowledge/port/VectorStore.java
  knowledge/port/VectorDocument.java
  knowledge/port/VectorSearchQuery.java
  knowledge/port/VectorSearchResult.java
  foundation/stub/StubModelGateway.java
  foundation/stub/FixedEmbeddingGateway.java
  foundation/stub/InMemoryVectorStore.java
  foundation/stub/StubFoundationConfiguration.java
src/main/resources/application.yml
src/test/java/com/finguard/copilot/
  config/CopilotPropertiesTest.java
  api/error/GlobalExceptionHandlerTest.java
  port/PortContractTest.java
  foundation/stub/StubModelGatewayTest.java
  foundation/stub/FixedEmbeddingGatewayTest.java
  foundation/stub/InMemoryVectorStoreTest.java
  foundation/stub/StubFoundationConfigurationTest.java
README.md
TASKS.md
```

### Task 1: Validated application configuration

**Files:**
- Modify: `src/main/java/com/finguard/copilot/FinGuardCopilotApplication.java`
- Create: `src/main/java/com/finguard/copilot/config/CopilotProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/finguard/copilot/config/CopilotPropertiesTest.java`

**Interfaces:**
- Produces: `CopilotProperties(Core core, Embedding embedding, Retrieval retrieval)`.
- Produces: `Core.baseUrl(): URI`, `Embedding.dimensions(): int`, `Retrieval.topK(): int`.
- Later task consumes: `CopilotProperties.embedding().dimensions()` when wiring `FixedEmbeddingGateway`.

- [ ] **Step 1: Write configuration binding tests**

Create an `ApplicationContextRunner` with `ConfigurationPropertiesAutoConfiguration`, `ValidationAutoConfiguration`, and a nested `@EnableConfigurationProperties(CopilotProperties.class)` configuration. Tests must cover these exact inputs:

```java
private static final String[] VALID_PROPERTIES = {
        "finguard.copilot.core.base-url=https://core.example.internal",
        "finguard.copilot.embedding.dimensions=16",
        "finguard.copilot.retrieval.top-k=7"
};

@Test
void bindsValidatedProperties() {
    contextRunner.withPropertyValues(VALID_PROPERTIES).run(context -> {
        assertThat(context).hasNotFailed();
        CopilotProperties properties = context.getBean(CopilotProperties.class);
        assertThat(properties.core().baseUrl())
                .isEqualTo(URI.create("https://core.example.internal"));
        assertThat(properties.embedding().dimensions()).isEqualTo(16);
        assertThat(properties.retrieval().topK()).isEqualTo(7);
    });
}
```

Add parameterized invalid cases for `ftp://core.example`, `https://user:pass@core.example`, dimensions `3` and `4097`, and Top-K `0` and `101`. Each case asserts `context.hasFailed()` and a root-cause message containing the violated property rule.

- [ ] **Step 2: Run the configuration test and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=CopilotPropertiesTest test
```

Expected: test compilation fails because `CopilotProperties` does not exist.

- [ ] **Step 3: Implement typed validated properties**

Create an immutable configuration record:

```java
@Validated
@ConfigurationProperties("finguard.copilot")
public record CopilotProperties(
        @Valid Core core,
        @Valid Embedding embedding,
        @Valid Retrieval retrieval
) {
    public record Core(@NotNull URI baseUrl) {
        @AssertTrue(message = "core.base-url must be an absolute HTTP(S) URI without credentials, query, or fragment")
        public boolean isSafeBaseUrl() {
            if (baseUrl == null || !baseUrl.isAbsolute()) {
                return false;
            }
            boolean http = "http".equalsIgnoreCase(baseUrl.getScheme())
                    || "https".equalsIgnoreCase(baseUrl.getScheme());
            return http && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
        }
    }

    public record Embedding(@Min(4) @Max(4096) int dimensions) {}

    public record Retrieval(@Min(1) @Max(100) int topK) {}
}
```

Add `@ConfigurationPropertiesScan` to `FinGuardCopilotApplication`.

Add the following YAML under `finguard.copilot`:

```yaml
finguard:
  copilot:
    core:
      base-url: ${FINGUARD_CORE_BASE_URL:http://localhost:8080}
    embedding:
      dimensions: ${COPILOT_EMBEDDING_DIMENSIONS:8}
    retrieval:
      top-k: ${COPILOT_RETRIEVAL_TOP_K:5}
```

- [ ] **Step 4: Run focused and existing application tests**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=CopilotPropertiesTest,FinGuardCopilotApplicationTests" test
```

Expected: all configuration cases and the existing application test pass.

- [ ] **Step 5: Commit after explicit authorization only**

```powershell
git add src/main/java/com/finguard/copilot/FinGuardCopilotApplication.java src/main/java/com/finguard/copilot/config/CopilotProperties.java src/main/resources/application.yml src/test/java/com/finguard/copilot/config/CopilotPropertiesTest.java
git commit -m "feat: validate copilot runtime configuration"
```

### Task 2: RFC 9457 API error boundary

**Files:**
- Create: `src/main/java/com/finguard/copilot/common/error/ErrorCode.java`
- Create: `src/main/java/com/finguard/copilot/common/error/CopilotException.java`
- Create: `src/main/java/com/finguard/copilot/api/error/GlobalExceptionHandler.java`
- Test: `src/test/java/com/finguard/copilot/api/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `CopilotException(ErrorCode code, String message)` and `code()`.
- Produces: RFC 9457 bodies containing `type`, `title`, `status`, `detail`, `instance`, and extension `code`.
- Error codes: `VALIDATION_FAILED`, `INVALID_REQUEST`, `RESOURCE_NOT_FOUND`, `CONFLICT`, `INTERNAL_ERROR`.

- [ ] **Step 1: Write failing standalone MockMvc tests**

Build MockMvc with a nested test controller and the real advice:

```java
mockMvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
```

The controller exposes test-only `/resource` and `/unexpected` methods. `/resource` throws:

```java
throw new CopilotException(ErrorCode.RESOURCE_NOT_FOUND, "Analysis run was not found");
```

Assert the first response is HTTP 404 with:

```text
type = urn:finguard:error:resource-not-found
title = Resource not found
status = 404
detail = Analysis run was not found
instance = /resource
code = RESOURCE_NOT_FOUND
```

`/unexpected` throws `new IllegalStateException("database-password=do-not-leak")`. Assert HTTP 500, `code=INTERNAL_ERROR`, `detail=The server could not process the request`, and that the serialized body does not contain `database-password` or `do-not-leak`.

- [ ] **Step 2: Run the handler test and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=GlobalExceptionHandlerTest test
```

Expected: compilation fails because the error types and advice do not exist.

- [ ] **Step 3: Implement framework-neutral errors and HTTP mapping**

`ErrorCode` stores only a safe title. `CopilotException` validates non-null code and message. `GlobalExceptionHandler` uses an internal method:

```java
private ResponseEntity<ProblemDetail> problem(
        ErrorCode code,
        HttpStatus status,
        String detail,
        HttpServletRequest request
) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("urn:finguard:error:" + kebabCase(code.name())));
    problem.setTitle(code.title());
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code.name());
    return ResponseEntity.status(status).body(problem);
}
```

Map resource-not-found to 404, conflict to 409, validation/invalid-request to 400, and internal error to 500. Handle `MethodArgumentNotValidException`, `BindException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `CopilotException`, and `Exception`. Log unexpected exceptions and return only the fixed safe detail.

- [ ] **Step 4: Run error tests and application smoke test**

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=GlobalExceptionHandlerTest,FinGuardCopilotApplicationTests" test
```

Expected: both handler cases and the application smoke test pass.

- [ ] **Step 5: Commit after explicit authorization only**

```powershell
git add src/main/java/com/finguard/copilot/common/error src/main/java/com/finguard/copilot/api/error src/test/java/com/finguard/copilot/api/error
git commit -m "feat: add safe RFC 9457 error responses"
```

### Task 3: Project-owned domain ports

**Files:**
- Create: `src/main/java/com/finguard/copilot/model/port/ModelGateway.java`
- Create: `src/main/java/com/finguard/copilot/model/port/ModelRequest.java`
- Create: `src/main/java/com/finguard/copilot/model/port/ModelResponse.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/EmbeddingGateway.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/EmbeddingVector.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/VectorStore.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/VectorDocument.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/VectorSearchQuery.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/port/VectorSearchResult.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/port/CoreClient.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/port/CurrentUser.java`
- Create: `src/main/java/com/finguard/copilot/coreclient/port/ReviewTaskContext.java`
- Test: `src/test/java/com/finguard/copilot/port/PortContractTest.java`

**Interfaces:**
- Produces: `ModelGateway.complete(ModelRequest): ModelResponse`.
- Produces: `EmbeddingGateway.embed(String): EmbeddingVector`.
- Produces: `VectorStore.upsert(VectorDocument)`, `search(VectorSearchQuery): List<VectorSearchResult>`, and `clear()`.
- Produces: `CoreClient.currentUser(String)` and `reviewTaskContext(long, String)`.

- [ ] **Step 1: Write failing value-contract tests**

Test that `EmbeddingVector` defensively copies its source list, rejects an empty list, null elements, `NaN`, and infinity, and returns the correct `dimension()`.

Test that `ModelRequest` rejects blank request ID/instructions/context and `ModelResponse` rejects blank model ID/content or negative token counts.

Construct a fully typed `ReviewTaskContext` with `BigDecimal("120000.00")` and `Instant.parse("2026-08-20T08:01:00Z")`; assert the amount retains scale 2, the task version is 3, and risk-fact collections are immutable.

- [ ] **Step 2: Run contract tests and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=PortContractTest test
```

Expected: compilation fails because the port records do not exist.

- [ ] **Step 3: Implement model and knowledge contracts**

Use these exact signatures:

```java
public interface ModelGateway {
    ModelResponse complete(ModelRequest request);
}

public record ModelRequest(String requestId, String instructions, String context) {}

public record ModelResponse(
        String modelId,
        String content,
        int inputTokens,
        int outputTokens
) {}

public interface EmbeddingGateway {
    EmbeddingVector embed(String text);
}

public interface VectorStore {
    void upsert(VectorDocument document);
    List<VectorSearchResult> search(VectorSearchQuery query);
    void clear();
}
```

`EmbeddingVector`, `VectorDocument`, `VectorSearchQuery`, and `VectorSearchResult` use compact constructors to enforce non-null values, positive Top-K, scores from `-1.0` through `1.0`, defensive collection copies, and nonblank IDs/index versions/text.

- [ ] **Step 4: Implement typed Core read contracts**

Use:

```java
public interface CoreClient {
    CurrentUser currentUser(String bearerToken);
    ReviewTaskContext reviewTaskContext(long reviewTaskId, String bearerToken);
}

public record CurrentUser(String username, Set<String> roles) {}
```

`ReviewTaskContext` contains nested immutable records with these exact fields:

```text
ReviewTask: long id, long version, String status, String ruleCode, String reasonCode, Instant createdAt
Transaction: long id, long accountId, BigDecimal amount, String currency, Instant occurredAt, String sourceType
Reconciliation: String resultType, String matchMethod, String reasonCode
RiskFact: String code, String value, String source
AccountSummary: String status, String displayName
ReviewTaskContext: ReviewTask reviewTask, Transaction transaction, Reconciliation reconciliation,
                   List<RiskFact> riskFacts, AccountSummary accountSummary, Instant capturedAt
```

Validate positive IDs/versions, nonnegative amount, required strings/timestamps, and immutable role/risk-fact collections. Do not add an HTTP implementation.

- [ ] **Step 5: Run the port tests**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=PortContractTest test
```

Expected: all value-contract tests pass.

- [ ] **Step 6: Audit port imports**

Run:

```powershell
rg -n "org\.springframework|com\.alibaba|io\.qdrant|jakarta" src/main/java/com/finguard/copilot/model/port src/main/java/com/finguard/copilot/knowledge/port src/main/java/com/finguard/copilot/coreclient/port
```

Expected: no matches.

- [ ] **Step 7: Commit after explicit authorization only**

```powershell
git add src/main/java/com/finguard/copilot/model src/main/java/com/finguard/copilot/knowledge src/main/java/com/finguard/copilot/coreclient src/test/java/com/finguard/copilot/port
git commit -m "feat: define copilot domain ports"
```

### Task 4: Deterministic stub adapters

**Files:**
- Create: `src/main/java/com/finguard/copilot/foundation/stub/StubModelGateway.java`
- Create: `src/main/java/com/finguard/copilot/foundation/stub/FixedEmbeddingGateway.java`
- Create: `src/main/java/com/finguard/copilot/foundation/stub/InMemoryVectorStore.java`
- Create: `src/main/java/com/finguard/copilot/foundation/stub/StubFoundationConfiguration.java`
- Test: `src/test/java/com/finguard/copilot/foundation/stub/StubModelGatewayTest.java`
- Test: `src/test/java/com/finguard/copilot/foundation/stub/FixedEmbeddingGatewayTest.java`
- Test: `src/test/java/com/finguard/copilot/foundation/stub/InMemoryVectorStoreTest.java`
- Test: `src/test/java/com/finguard/copilot/foundation/stub/StubFoundationConfigurationTest.java`

**Interfaces:**
- Consumes: all ports and records from Task 3.
- Consumes: `CopilotProperties.embedding().dimensions()` from Task 1.
- Produces: deterministic `ModelGateway`, `EmbeddingGateway`, and `VectorStore` beans under profile `stub`.

- [ ] **Step 1: Write the failing Stub model test**

Instantiate `StubModelGateway`, call `complete` twice with the same `ModelRequest`, and assert equal `ModelResponse` values. Assert model ID `stub-deterministic-v1`, content `stub:req-001`, and token counts `0`. Call with request ID `req-002` and assert a different generic content. Assert no response contains `CONFIRMED` or `IGNORED`.

- [ ] **Step 2: Run the Stub model test and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=StubModelGatewayTest test
```

Expected: compilation fails because `StubModelGateway` does not exist.

- [ ] **Step 3: Implement and verify Stub model**

Return:

```java
return new ModelResponse(
        "stub-deterministic-v1",
        "stub:" + request.requestId(),
        0,
        0
);
```

Run the same focused test and expect PASS.

- [ ] **Step 4: Write failing fixed-embedding tests**

For dimension 8, assert identical text produces exactly equal vectors, different text produces a different vector, every component is finite, dimension is 8, and L2 norm is within `1.0e-12` of `1.0`. Assert constructor dimensions 3 and 4097 are rejected.

- [ ] **Step 5: Run embedding tests and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=FixedEmbeddingGatewayTest test
```

Expected: compilation fails because `FixedEmbeddingGateway` does not exist.

- [ ] **Step 6: Implement SHA-256 fixed embedding**

Hash UTF-8 text with `MessageDigest.getInstance("SHA-256")`, cycle through digest bytes to fill the configured dimension, map each unsigned byte to `[-1.0, 1.0]`, and divide by L2 norm. Throw `IllegalStateException` only if SHA-256 is unavailable. Validate dimension in the constructor.

Run the focused test and expect PASS.

- [ ] **Step 7: Write failing in-memory retrieval tests**

Insert three documents: two in index `v1` and one exact-match vector in `v2`. Search `v1` with Top-K 1 and assert the closest `v1` document wins while the `v2` document is excluded. Add two equal-score `v1` documents and assert IDs sort ascending. Insert a dimension-2 document and search that index with a dimension-3 query; assert `IllegalArgumentException` contains `dimension`.

- [ ] **Step 8: Run vector-store tests and verify RED**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=InMemoryVectorStoreTest test
```

Expected: compilation fails because `InMemoryVectorStore` does not exist.

- [ ] **Step 9: Implement deterministic in-memory retrieval**

Use `ConcurrentHashMap<DocumentKey, VectorDocument>` where `DocumentKey` contains index version and document ID. Filter before scoring, reject dimension mismatch, calculate cosine similarity, and sort with:

```java
Comparator.comparingDouble(VectorSearchResult::score)
        .reversed()
        .thenComparing(result -> result.document().id())
```

Limit results to `query.topK()` and return an immutable list. `clear()` removes all documents.

Run the focused test and expect PASS.

- [ ] **Step 10: Write and implement explicit stub-profile wiring test**

Use `ApplicationContextRunner` with `@EnableConfigurationProperties(CopilotProperties.class)` and `StubFoundationConfiguration`. Without `spring.profiles.active=stub`, assert the three port beans are absent. With the profile and valid properties, assert exactly one `ModelGateway`, `EmbeddingGateway`, and `VectorStore` exists and the embedding bean returns dimension 8.

Implement:

```java
@Configuration(proxyBeanMethods = false)
@Profile("stub")
public class StubFoundationConfiguration {
    @Bean
    ModelGateway stubModelGateway() { return new StubModelGateway(); }

    @Bean
    EmbeddingGateway fixedEmbeddingGateway(CopilotProperties properties) {
        return new FixedEmbeddingGateway(properties.embedding().dimensions());
    }

    @Bean
    VectorStore inMemoryVectorStore() { return new InMemoryVectorStore(); }
}
```

Run:

```powershell
.\mvnw.cmd -B -ntp "-Dtest=StubModelGatewayTest,FixedEmbeddingGatewayTest,InMemoryVectorStoreTest,StubFoundationConfigurationTest" test
```

Expected: every deterministic adapter and profile-wiring test passes.

- [ ] **Step 11: Commit after explicit authorization only**

```powershell
git add src/main/java/com/finguard/copilot/foundation src/test/java/com/finguard/copilot/foundation
git commit -m "feat: add deterministic offline adapters"
```

### Task 5: Phase 0 closeout and full verification

**Files:**
- Modify: `README.md`
- Modify: `TASKS.md`
- Modify: `docs/ARCHITECTURE.md` only if package names need synchronization.

**Interfaces:**
- Consumes: executable evidence from Tasks 1 through 4.
- Produces: an honest Phase 0 completion statement and repeatable verification evidence.

- [ ] **Step 1: Run the complete Java suite**

Run:

```powershell
.\mvnw.cmd -B -ntp clean test
```

Expected: all Java unit, configuration, MockMvc, profile, OpenAPI, and two MySQL Testcontainers tests pass with zero failures, errors, or skips.

- [ ] **Step 2: Update Phase 0 documentation**

In `TASKS.md`, check exactly these remaining Phase 0 items after their tests pass:

```text
建立统一错误响应和启动配置校验。
定义模型、Embedding、向量存储和 Core 客户端端口。
建立 Stub 模型、固定 Embedding 和内存检索实现。
```

Update `README.md` to state that Phase 0 is complete while Core HTTP, structured analysis, real models, Qdrant, RAG, and Agent Workflow remain unimplemented. Document the explicit `stub` profile and state that it is for tests/development only.

- [ ] **Step 3: Run the unified multi-language verification**

Run:

```powershell
.\scripts\verify.ps1
```

Expected: Java, Python, frontend lint/tests/build, and whitespace checks all exit 0.

- [ ] **Step 4: Audit scope and secrets**

Run:

```powershell
rg -n "spring-ai-.*starter|qdrant|redis|rabbitmq|langfuse|langgraph" pom.xml frontend/package.json tools/agent-evaluation/pyproject.toml
rg -n "org\.springframework|com\.alibaba|io\.qdrant|jakarta" src/main/java/com/finguard/copilot/model/port src/main/java/com/finguard/copilot/knowledge/port src/main/java/com/finguard/copilot/coreclient/port
rg --hidden -n -i "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY" -g '!*.md'
git diff --check
git status --short
```

Expected: no deferred runtime dependencies, no framework imports in ports, no private keys, no whitespace errors, and only planned uncommitted source/documentation files.

- [ ] **Step 5: Verify no test infrastructure remains**

Run:

```powershell
docker ps --filter "label=org.testcontainers=true" --format "{{.ID}} {{.Image}} {{.Status}}"
```

Expected: no Testcontainers remain after Maven exits.

- [ ] **Step 6: Commit after explicit authorization only**

```powershell
git add README.md TASKS.md docs/ARCHITECTURE.md
git commit -m "docs: close phase zero foundation"
```

## Phase 0 Acceptance

- All eleven Phase 0 checklist items are checked with direct evidence.
- Invalid configuration prevents startup and valid configuration binds typed values.
- RFC 9457 errors are stable and unexpected exception details cannot leak.
- Model, embedding, vector-store, and Core client ports compile without framework imports.
- Stub model, fixed embedding, and in-memory retrieval are deterministic and profile-gated.
- No real model, Core HTTP, Qdrant, RAG, messaging, Redis, Agent, or observability runtime is falsely claimed.
- Full Java/Python/frontend verification and repository audits pass.
- No commit or push occurs without explicit user authorization.
