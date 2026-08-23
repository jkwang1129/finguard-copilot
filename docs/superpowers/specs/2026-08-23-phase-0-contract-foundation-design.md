# Phase 0 Contract Foundation Design

## 1. Objective

Complete the three remaining Phase 0 gates without implementing Phase 1 business behavior:

1. unified API errors and startup configuration validation;
2. stable model, embedding, vector-store, and Core client ports;
3. deterministic Stub model, fixed embedding, and in-memory vector retrieval.

The result must build and test without a model key, paid API, Core instance, Qdrant, Redis, RabbitMQ, or internet access at test runtime. The new contracts are foundations for later adapters, not claims that model, RAG, or Core integration already works.

## 2. Chosen Approach

Use project-owned domain ports and records, with deterministic test adapters in a dedicated `stub` profile. Do not add Spring AI model, embedding, or vector-store starters during Phase 0.

This preserves the architecture rule that the domain depends on stable project contracts while framework and provider types remain in adapters. It also prevents unused dependencies from being presented as implemented capability.

Rejected approaches:

- Depending directly on Spring AI interfaces now would couple the domain to framework contracts before a real adapter or evaluation exists.
- Leaving the ports as documentation-only would not satisfy the Phase 0 roadmap or prove deterministic offline behavior.
- Implementing Phase 1 structured analysis now would mix contract foundation with Core authentication, analysis orchestration, and business validation.

## 3. Package and Dependency Boundaries

```text
com.finguard.copilot
├── api.error                 HTTP ProblemDetail mapping only
├── common.error              framework-neutral error code and exception
├── config                    validated application properties
├── coreclient.port           Core read-only port and typed Core facts
├── model.port                model request/response and gateway
├── knowledge.port            embedding and vector-store contracts
└── foundation.stub           deterministic adapters and stub-profile wiring
```

Allowed dependencies:

- `api.error` may depend on Spring MVC and `common.error`.
- `config` may depend on Spring Boot configuration binding and Jakarta Validation.
- port packages use the Java standard library only.
- `foundation.stub` may depend on the port packages and Java cryptography/collections.
- no port may import Spring AI, an HTTP client, Qdrant, or a provider SDK.

## 4. Validated Configuration

Add `CopilotProperties` with prefix `finguard.copilot` and enable configuration-properties scanning from the application entrypoint.

| Property | Default | Validation |
|---|---:|---|
| `core.base-url` | `http://localhost:8080` | absolute `http` or `https` URI; no user info, query, or fragment |
| `embedding.dimensions` | `8` | integer from 4 through 4096 |
| `retrieval.top-k` | `5` | integer from 1 through 100 |

`application.yml` maps these values from `FINGUARD_CORE_BASE_URL`, `COPILOT_EMBEDDING_DIMENSIONS`, and `COPILOT_RETRIEVAL_TOP_K`. The localhost Core URL is a safe development default and is never contacted in Phase 0. Database credentials remain required environment values with no password default.

Configuration tests must prove that a malformed or credential-bearing Core URI and out-of-range numeric values prevent context startup. A valid configuration must bind to the exact typed values.

## 5. Unified API Error Contract

Use Spring `ProblemDetail` as the RFC 9457 response format. Every error response includes:

```json
{
  "type": "urn:finguard:error:validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "A safe client-facing explanation",
  "instance": "/api/example",
  "code": "VALIDATION_FAILED"
}
```

`ErrorCode` and `CopilotException` remain framework-neutral. `GlobalExceptionHandler` maps:

- `CopilotException` to its explicit safe code and configured HTTP status;
- method argument and binding failures to `VALIDATION_FAILED` with HTTP 400;
- malformed request bodies to `INVALID_REQUEST` with HTTP 400;
- unexpected exceptions to `INTERNAL_ERROR` with HTTP 500 and a generic detail.

Unexpected exceptions are logged server-side, but stack traces, SQL, model output, tokens, and upstream bodies never enter the response. Handler tests use a test-only controller; no fake production endpoint is added merely to exercise error handling.

## 6. Port Contracts

### 6.1 Model

`ModelGateway.complete(ModelRequest)` returns `ModelResponse`.

- `ModelRequest`: request identifier, system instructions, and context text.
- `ModelResponse`: model identifier, content, input token count, and output token count.

The contract is deliberately generic in Phase 0. Structured review recommendations and their JSON/fact validation belong to Phase 1.

### 6.2 Embedding

`EmbeddingGateway.embed(String)` returns an immutable `EmbeddingVector`.

`EmbeddingVector` rejects empty vectors and non-finite values, exposes its dimension, and defensively copies values. Batch behavior can be added later only when an ingestion pipeline requires it.

### 6.3 Vector Store

`VectorStore` exposes only the behavior required by deterministic retrieval tests:

- `upsert(VectorDocument)`;
- `search(VectorSearchQuery)`;
- `clear()`.

`VectorDocument` contains a document ID, index version, source text, embedding, and string metadata. `VectorSearchQuery` contains an index version, query embedding, and Top-K. `VectorSearchResult` contains the document and cosine score. Search never crosses index versions.

Future Qdrant ingestion, candidate-index publication, deletion, and rollback extend adapters and application services around this port in Phase 2; they are not simulated here.

### 6.4 Core Client

`CoreClient` defines the two approved read-only operations:

- obtain the current user identity;
- obtain a versioned review-task context.

The typed context follows the logical schema already approved in `docs/ARCHITECTURE.md`: review task, transaction, reconciliation, risk facts, account summary, and capture time. Monetary values use `BigDecimal`, timestamps use `Instant`, and evolving Core status/reason codes remain strings at this boundary.

The bearer token is supplied per invocation and is never stored by the port. No HTTP implementation, base-path construction, retry, or authentication behavior is added in Phase 0.

## 7. Deterministic Adapters

### 7.1 Stub Model

`StubModelGateway` returns the same response for the same request and performs no I/O. Its output is a generic stub payload, not a fabricated successful financial recommendation. Token counts are deterministic and explicitly labeled as stub values.

### 7.2 Fixed Embedding

`FixedEmbeddingGateway` derives a fixed-dimension vector from SHA-256 bytes and normalizes it. The same UTF-8 input and dimension always produce exactly the same vector across runs. It is intended for repeatable tests, not semantic-quality claims.

### 7.3 In-Memory Vector Store

`InMemoryVectorStore` keeps immutable documents in a thread-safe map, filters by index version, calculates cosine similarity, sorts by descending score, and breaks equal-score ties by document ID. It rejects dimension mismatches instead of truncating or padding vectors.

The three adapters are exposed as Spring beans only under the explicit `stub` profile. The default runtime therefore cannot silently masquerade as a real model or vector service.

## 8. Test Strategy

Follow red-green-refactor for each observable behavior:

1. configuration binding tests fail for invalid URI/dimensions/Top-K and pass for valid values;
2. standalone MockMvc tests verify the exact RFC 9457 body and safe 500 response;
3. model tests prove identical requests produce identical outputs and no provider dependency exists;
4. embedding tests prove exact repeatability, configured dimension, finite values, and input differentiation;
5. vector-store tests prove version isolation, cosine ranking, Top-K, deterministic ties, and dimension rejection;
6. a stub-profile context test proves the deterministic beans are wired only when explicitly enabled;
7. the existing MySQL, OpenAPI, Python, and React tests remain green.

No test may be disabled or skipped when Docker is available. Normal tests must not read a model key or make an outbound request.

## 9. Documentation and Acceptance

Phase 0 is complete only when:

- all three previously unchecked Phase 0 tasks in `TASKS.md` have executable evidence;
- `README.md` distinguishes the completed foundation from unimplemented Agent behavior;
- no Spring AI starter, provider SDK, Qdrant client, or Core HTTP implementation was added;
- Java, Python, and frontend verification scripts pass with no skipped tests;
- secret, private-key, placeholder, whitespace, deferred-runtime, and Git-scope audits pass;
- the work remains uncommitted and unpushed until the user explicitly authorizes Git integration.

## 10. Explicit Non-Goals

- Core authentication forwarding or HTTP calls;
- analysis creation, persistence, state machine, Outbox, or RabbitMQ;
- structured financial recommendation generation;
- Qdrant or document ingestion;
- real model, provider routing, prompt versioning, or cost accounting;
- Agent Graph, Tool Calling, Working Memory, HITL, MCP, Redis, or Langfuse.
