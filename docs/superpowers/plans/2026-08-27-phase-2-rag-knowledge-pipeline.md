# Phase 2 RAG Knowledge Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a versioned, auditable RAG knowledge pipeline that can publish policy and runbook evidence for Phase 1 analyses without storing dynamic Core facts in the vector index.

**Architecture:** MySQL stores document, chunk, checksum, index-version, publication, and citation metadata; Qdrant stores only chunk vectors and searchable metadata. A candidate index is built and evaluated before an atomic active-version switch. Runtime retrieval reads one published version and returns citation-bearing results that the output guardrail validates against the retrieved set.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Maven, MySQL/Flyway, Testcontainers, Spring `RestClient` for the Qdrant adapter, deterministic fixed embeddings and in-memory vector storage for ordinary tests, Qdrant for integration tests, Python 3.12/uv/pytest for the fixed RAG evaluation dataset.

**Spec:** `docs/ROADMAP.md` (阶段 2：RAG 与知识数据管道), `TASKS.md` (阶段 2), `docs/SCOPE.md`, and sections 7–10 of `docs/ARCHITECTURE.md`.

## Global Constraints

- Phase 2 delivers document registration, parsing, cleaning, chunking, deduplication, embeddings, Qdrant integration, candidate evaluation, atomic publication, incremental update, deletion, rollback, citation validation, and a fixed RAG evaluation set.
- Dynamic Core facts, transaction amounts, review-task state, JWTs, passwords, raw CSV files, model prompts, and arbitrary URLs never enter the knowledge index.
- Runtime retrieval reads exactly one explicitly published index version; a failed candidate build or evaluation cannot change the active version.
- Ordinary tests remain runnable without model keys, paid APIs, internet access, or a running Qdrant instance; Qdrant and MySQL tests use isolated Testcontainers and must not be silently skipped.
- Embedding input and chunk metadata are deterministic: canonical UTF-8 text, explicit document/version/chunk identifiers, stable checksums, and fixed test vectors.
- Only versioned policies, rule explanations, manuals, incident/runbook material, and fully sanitized cases are admissible knowledge sources.
- Do not add BM25, Elasticsearch, reranking, Redis, LangGraph, MCP, Tool Calling, real model providers, SSE, or UI behavior in this phase.
- Do not edit applied Flyway migrations; add an additive migration after the current Phase 1 migration.
- Commit commands are checkpoints only and must not be run until the user explicitly authorizes a commit; do not push without separate authorization.

## Repository Map

- `src/main/java/com/finguard/copilot/knowledge/` owns document, chunk, index-version, retrieval, citation, and vector-adapter code.
- `src/main/java/com/finguard/copilot/analysis/` consumes published knowledge citations but remains independent of provider SDKs.
- `src/main/java/com/finguard/copilot/guardrail/` validates that output citations are members of the retrieved, published evidence set.
- `src/main/resources/db/migration/V3__create_phase2_knowledge_tables.sql` adds Phase 2 metadata tables; Phase 1 `V1` and `V2` remain immutable.
- `tools/agent-evaluation/` stores sanitized, fixed RAG cases and offline metric calculation; it never connects directly to MySQL or Qdrant.
- `docs/acceptance/phase-2-rag-knowledge-pipeline.md` records fresh commands, counts, publication/rollback evidence, and known limitations.

### Task 1: Lock the knowledge domain and persistence contracts

**Files:**
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeDocument.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeChunk.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeIndexVersion.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeCitation.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeSourceType.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/domain/KnowledgeIndexStatus.java`
- Create: `src/main/resources/db/migration/V3__create_phase2_knowledge_tables.sql`
- Create: `src/test/java/com/finguard/copilot/knowledge/domain/KnowledgeDomainTest.java`
- Create: `src/test/java/com/finguard/copilot/Phase2MigrationTest.java`
- Modify: `docs/ARCHITECTURE.md` only if the final table or status names differ from the documented model.

**Interfaces:**
- `KnowledgeDocument` exposes `documentId`, `sourceType`, `title`, `canonicalChecksum`, `sanitized`, `sourceVersion`, `registeredAt`, and `status`.
- `KnowledgeChunk` exposes `chunkId`, `documentId`, `documentVersion`, `ordinal`, `content`, `contentChecksum`, `sectionPath`, and immutable metadata.
- `KnowledgeIndexVersion` exposes `indexVersion`, `status`, `createdAt`, `publishedAt`, and `replacedVersion`.
- `KnowledgeCitation` exposes `documentId`, `documentVersion`, `chunkId`, `sectionPath`, `contentChecksum`, and the retrieved score.
- Only `POLICY`, `RULE_EXPLANATION`, `MANUAL`, `RUNBOOK`, and `SANITIZED_CASE` source types are accepted; `DYNAMIC_CORE_FACT` is rejected.

- [x] **Step 1: Write domain tests for admissible sources and immutable identifiers**

```java
@Test
void rejectsDynamicCoreFacts() {
    assertThatThrownBy(() -> KnowledgeDocument.register(
            "doc-1", KnowledgeSourceType.DYNAMIC_CORE_FACT, "task", "v1", "abc", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void chunkIdentityIncludesDocumentVersionAndOrdinal() {
    KnowledgeChunk chunk = KnowledgeChunk.create("doc-1", "v1", 0, "policy text", "Risk", Map.of());
    assertThat(chunk.chunkId()).isEqualTo("doc-1:v1:0");
}
```

- [ ] **Step 2: Run the domain tests and confirm the contracts are missing**

Run: `./mvnw.cmd -B -ntp -Dtest=KnowledgeDomainTest test`

Expected: FAIL because the Phase 2 domain types and factories do not yet exist.

- [x] **Step 3: Implement the immutable records and source-type allowlist**

Use defensive copies for metadata, require nonblank identifiers and checksums, normalize section paths, and reject dynamic facts at construction time. Keep all domain classes free of Spring, Qdrant, JDBC, and HTTP imports.

- [x] **Step 4: Add the additive MySQL migration and schema assertions**

Create tables for `knowledge_document`, `knowledge_chunk`, `knowledge_index_version`, `knowledge_index_membership`, and `knowledge_citation`. Add unique constraints for document/version checksums, `(document_id, document_version, ordinal)`, and `(index_version, chunk_id)`; add foreign keys and a status check. `Phase2MigrationTest` must assert table names, required columns, indexes, and that V1/V2 remain unchanged.

- [x] **Step 5: Run focused tests and commit only if authorized**

Run: `./mvnw.cmd -B -ntp -Dtest=KnowledgeDomainTest,Phase2MigrationTest test`

Expected: domain tests pass; migration tests pass with Docker available. Checkpoint: `git add src/main/java/com/finguard/copilot/knowledge/domain src/main/resources/db/migration/V3__create_phase2_knowledge_tables.sql src/test/java/com/finguard/copilot/knowledge/domain src/test/java/com/finguard/copilot/Phase2MigrationTest.java docs/ARCHITECTURE.md && git commit -m "feat: define phase two knowledge contracts"` (do not run without authorization).

### Task 2: Implement deterministic document ingestion and chunking

**Files:**
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/KnowledgeDocumentRequest.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/KnowledgeIngestionService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/CanonicalDocument.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/DocumentCleaner.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/DocumentChunker.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/ingest/KnowledgeAdmissionPolicy.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/persistence/KnowledgeDocumentRepository.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/persistence/JdbcKnowledgeDocumentRepository.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/ingest/DocumentCleanerTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/ingest/DocumentChunkerTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/ingest/KnowledgeIngestionServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/ingest/KnowledgeDynamicFactRejectionTest.java`

**Interfaces:**
- `KnowledgeIngestionService.register(KnowledgeDocumentRequest request) -> KnowledgeDocument`.
- `DocumentCleaner.canonicalize(String rawText) -> CanonicalDocument`.
- `DocumentChunker.chunk(CanonicalDocument document) -> List<KnowledgeChunk>`.
- `KnowledgeAdmissionPolicy.check(KnowledgeDocumentRequest request, CanonicalDocument document) -> void`.

- [x] **Step 1: Write failing tests for canonicalization, section-aware chunking, and deduplication**

Test BOM removal, CRLF/LF normalization, Unicode whitespace normalization, stable SHA-256 checksums, heading-preserving chunks, deterministic overlap, duplicate content, and a document containing `transactionId`, amount, or review status being rejected.

- [ ] **Step 2: Run the focused ingestion tests and verify they fail**

Run: `./mvnw.cmd -B -ntp -Dtest=DocumentCleanerTest,DocumentChunkerTest,KnowledgeIngestionServiceTest,KnowledgeDynamicFactRejectionTest test`

Expected: FAIL because the ingestion pipeline is absent.

- [x] **Step 3: Implement canonicalization and section-aware chunking**

Hash the canonical UTF-8 bytes, preserve the source version, split first on headings and paragraph boundaries, enforce a configured maximum character/token proxy, and generate chunk IDs from document ID, document version, and ordinal. Never copy raw upload bytes into the vector store.

- [x] **Step 4: Implement admission, registration, and persistence**

Reject dynamic Core facts, secrets, JWT-like strings, arbitrary URLs, and unsanitized case fields before persistence. Register the document and chunks idempotently by canonical checksum; a same-checksum re-registration returns the existing document version without creating duplicate chunks.

- [x] **Step 5: Run the green tests and commit only if authorized**

Run: `./mvnw.cmd -B -ntp -Dtest=DocumentCleanerTest,DocumentChunkerTest,KnowledgeIngestionServiceTest,KnowledgeDynamicFactRejectionTest test`

Expected: all focused ingestion tests pass. Checkpoint: `git add src/main/java/com/finguard/copilot/knowledge/ingest src/main/java/com/finguard/copilot/knowledge/persistence src/test/java/com/finguard/copilot/knowledge/ingest && git commit -m "feat: add deterministic knowledge ingestion"` (do not run without authorization).

### Task 3: Add fixed embeddings and the Qdrant vector adapter

**Files:**
- Modify: `src/main/java/com/finguard/copilot/knowledge/port/EmbeddingGateway.java` only if the existing signature cannot carry model/version metadata.
- Modify: `src/main/java/com/finguard/copilot/knowledge/port/VectorStore.java` only if the existing signature cannot create, replace, delete, and search an index version.
- Create: `src/main/java/com/finguard/copilot/knowledge/qdrant/QdrantVectorStore.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/qdrant/QdrantClient.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/qdrant/QdrantConfiguration.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/qdrant/QdrantException.java`
- Modify: `src/main/java/com/finguard/copilot/foundation/stub/FixedEmbeddingGateway.java` only for the shared versioned embedding contract.
- Create: `src/test/java/com/finguard/copilot/knowledge/qdrant/QdrantVectorStoreTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/qdrant/QdrantVectorStoreIntegrationTest.java`
- Modify: `src/test/java/com/finguard/copilot/foundation/stub/FixedEmbeddingGatewayTest.java` if the contract changes.
- Modify: `pom.xml` to add only the selected Qdrant HTTP/client dependency at a pinned version, if the adapter cannot use the existing Spring HTTP stack.
- Modify: `src/main/resources/application.yml` to add an explicit `qdrant` profile with endpoint, collection prefix, timeout, and vector dimension.

**Interfaces:**
- `EmbeddingGateway.embed(String canonicalText, String embeddingVersion) -> EmbeddingVector`.
- `VectorStore.createIndex(String indexVersion, int dimension) -> void`.
- `VectorStore.upsert(String indexVersion, List<VectorDocument> documents) -> void`.
- `VectorStore.search(VectorSearchQuery query) -> List<VectorSearchResult>`.
- `VectorStore.deleteIndex(String indexVersion) -> void`.

- [x] **Step 1: Extend port tests before changing adapters**

Assert that embedding version, dimension, index version, top-K, metadata, and score bounds are mandatory; assert that the fixed embedding produces the same vector for the same canonical text and version.

- [ ] **Step 2: Run port and stub tests to establish the failing contract**

Run: `./mvnw.cmd -B -ntp -Dtest=PortContractTest,FixedEmbeddingGatewayTest,QdrantVectorStoreTest test`

Expected: the new Qdrant adapter contract tests fail while existing Phase 1 port tests remain understandable and targeted.

- [x] **Step 3: Implement the deterministic in-memory versioned behavior**

Make the in-memory store isolate documents by index version, sort results by descending score then chunk ID, and never search retired or unspecified versions.

- [x] **Step 4: Implement the Qdrant adapter behind an explicit profile**

Create collections per index version with a fixed distance and dimension, upsert only `KnowledgeChunk` metadata, use bounded connect/read timeouts, map non-2xx and malformed responses to `KNOWLEDGE_UNAVAILABLE`, and never log document content or request bodies.

- [x] **Step 5: Run unit tests and a real Qdrant integration test**

Run: `./mvnw.cmd -B -ntp -Dtest=PortContractTest,FixedEmbeddingGatewayTest,QdrantVectorStoreTest,QdrantVectorStoreIntegrationTest test`

Expected: ordinary tests pass; the integration test creates an isolated Qdrant container, inserts two versioned chunks, verifies version isolation and top-K ordering, then deletes the container and leaves no resources.

### Task 4: Build, evaluate, and atomically publish candidate indexes

**Files:**
- Create: `src/main/java/com/finguard/copilot/knowledge/index/KnowledgeIndexBuildService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/index/KnowledgeIndexEvaluationService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/index/KnowledgeIndexPublicationService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/persistence/KnowledgeIndexRepository.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/persistence/JdbcKnowledgeIndexRepository.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexBuildServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexPublicationServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexFailureIsolationTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexIntegrationTest.java`
- Create: `tools/agent-evaluation/src/finguard_agent_evaluation/rag_cases.py`
- Create: `tools/agent-evaluation/src/finguard_agent_evaluation/rag_metrics.py`
- Create: `tools/agent-evaluation/tests/test_rag_metrics.py`

**Interfaces:**
- `KnowledgeIndexBuildService.build(String indexVersion, List<KnowledgeChunk> chunks) -> KnowledgeIndexVersion`.
- `KnowledgeIndexEvaluationService.evaluate(String indexVersion, List<RagCase> cases) -> RagEvaluationResult`.
- `KnowledgeIndexPublicationService.publish(String candidateVersion, RagEvaluationResult result) -> KnowledgeIndexVersion`.
- `KnowledgeIndexRepository.findPublished() -> Optional<KnowledgeIndexVersion>` and `compareAndPublish(candidateVersion, expectedCurrentVersion) -> void`.

- [x] **Step 1: Define the fixed sanitized RAG case format and failing metric tests**

Use JSON fixtures with `caseId`, sanitized query, expected chunk IDs, required citation document/version, and forbidden chunk IDs. Test recall@K, citation precision, no-answer correctness, and deterministic ordering.

- [x] **Step 2: Implement candidate build and offline evaluation**

Create the candidate metadata row, embed canonical chunks, upsert them into a candidate index, run fixed cases against the candidate only, and persist the metrics and evaluator version. Do not expose the candidate to runtime reads.

- [x] **Step 3: Implement atomic publication**

In one MySQL transaction, lock the current published row, require the candidate to be evaluated and above the configured minimums, mark the old version retired, and mark the candidate published. Runtime lookup must see either the old or the new version, never an intermediate state.

- [x] **Step 4: Test failure isolation and concurrency**

Prove that embedding failure, Qdrant upsert failure, evaluation failure, threshold failure, and a stale publication request leave the previous published version unchanged and do not make candidate chunks searchable through `findPublished()`.

- [x] **Step 5: Run focused, Python, and MySQL/Qdrant integration tests**

Run: `./mvnw.cmd -B -ntp -Dtest=KnowledgeIndexBuildServiceTest,KnowledgeIndexPublicationServiceTest,KnowledgeIndexFailureIsolationTest,KnowledgeIndexIntegrationTest test`; then `Push-Location tools\agent-evaluation; uv run --python 3.12 pytest -q; Pop-Location`.

Expected: all metrics are deterministic; a successful publication changes exactly one active pointer; all failure cases preserve the old published version.

### Task 5: Add incremental update, deletion, and rollback

**Files:**
- Create: `src/main/java/com/finguard/copilot/knowledge/index/KnowledgeIndexChangeService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/index/KnowledgeIndexRollbackService.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexChangeServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexRollbackServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/index/KnowledgeIndexChangeIntegrationTest.java`
- Modify: `src/main/java/com/finguard/copilot/knowledge/persistence/KnowledgeDocumentRepository.java`
- Modify: `src/main/java/com/finguard/copilot/knowledge/persistence/KnowledgeIndexRepository.java`
- Modify: `src/main/java/com/finguard/copilot/knowledge/qdrant/QdrantVectorStore.java`

**Interfaces:**
- `KnowledgeIndexChangeService.replaceDocument(String documentId, String newVersion, byte[] sanitizedContent) -> KnowledgeIndexVersion`.
- `KnowledgeIndexChangeService.deleteDocument(String documentId, String documentVersion) -> KnowledgeIndexVersion`.
- `KnowledgeIndexRollbackService.rollback(String targetPublishedVersion, String currentPublishedVersion) -> KnowledgeIndexVersion`.

- [x] **Step 1: Write failing tests for update, deletion, and rollback semantics**

Test that an updated document creates new chunks and a new candidate, deleted chunks disappear from the next published version, a failed publication keeps the prior content, and rollback restores the exact prior document/version/checksum set.

- [x] **Step 2: Implement versioned change sets**

Treat updates and deletions as a new candidate version derived from the current published membership plus the requested change. Never mutate already-published chunk content in place.

- [x] **Step 3: Implement rollback as a normal evaluated publication**

Require the target version to exist, remain internally consistent, and have retained Qdrant data. Publish it through the same compare-and-swap path; if its vector data is missing, fail closed and keep the current version active.

- [x] **Step 4: Verify old-version isolation after update/delete/rollback**

Search the active version and explicit historical versions in integration tests, assert deleted content is absent from the new active version, and assert no runtime query defaults to a retired version.

- [x] **Step 5: Run tests and commit only if authorized**

Run: `./mvnw.cmd -B -ntp -Dtest=KnowledgeIndexChangeServiceTest,KnowledgeIndexRollbackServiceTest,KnowledgeIndexChangeIntegrationTest test`.

Expected: update, deletion, failed publication, and rollback tests pass with no in-place mutation of a published version.

### Task 6: Integrate retrieval citations and output citation validation

**Files:**
- Create: `src/main/java/com/finguard/copilot/knowledge/retrieval/KnowledgeRetrievalService.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/retrieval/KnowledgeEvidence.java`
- Create: `src/main/java/com/finguard/copilot/knowledge/retrieval/KnowledgeCitationValidator.java`
- Modify: `src/main/java/com/finguard/copilot/analysis/application/DeterministicAnalysisOrchestrator.java`
- Modify: `src/main/java/com/finguard/copilot/analysis/domain/AnalysisResult.java`
- Modify: `src/main/java/com/finguard/copilot/guardrail/AnalysisOutputSchemaValidator.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/retrieval/KnowledgeRetrievalServiceTest.java`
- Create: `src/test/java/com/finguard/copilot/knowledge/retrieval/KnowledgeCitationValidatorTest.java`
- Create: `src/test/java/com/finguard/copilot/analysis/Phase2CitationIntegrationTest.java`

**Interfaces:**
- `KnowledgeRetrievalService.retrieve(String query, int topK) -> List<KnowledgeEvidence>`; it resolves the single published index before searching.
- `KnowledgeCitationValidator.validate(List<KnowledgeCitation> citations, List<KnowledgeEvidence> retrieved) -> void`.
- A citation is valid only when document ID, document version, chunk ID, section path, checksum, and index version match retrieved evidence.

- [x] **Step 1: Write failing retrieval and citation tests**

Test no published index, unavailable vector store, missing citation, fabricated document/version/chunk IDs, checksum mismatch, duplicate citations, and a valid citation set. Confirm failures produce `KNOWLEDGE_UNAVAILABLE` or `CITATION_VALIDATION_FAILED` without a directional recommendation.

- [x] **Step 2: Implement published-version retrieval**

Resolve the active version once per analysis, embed the query using the pinned embedding version, request bounded top-K results, and map vector metadata to immutable `KnowledgeEvidence`. Never include dynamic Core facts in the query text or index metadata.

- [x] **Step 3: Implement citation validation and safe orchestrator integration**

Keep Phase 1 Stub behavior safe: when no published policy evidence exists, return `INSUFFICIENT_EVIDENCE`; when evidence exists, attach only validated citations and retain the same safe recommendation until Phase 3’s real-model gate is passed.

- [x] **Step 4: Verify output-schema and fact guardrails together**

Run: `./mvnw.cmd -B -ntp -Dtest=KnowledgeRetrievalServiceTest,KnowledgeCitationValidatorTest,Phase2CitationIntegrationTest,AnalysisOutputSchemaValidatorTest,FactConsistencyValidatorTest test`.

Expected: valid citations survive serialization; any fabricated, stale, or non-retrieved citation fails closed and does not leak raw vector-store responses.

### Task 7: Cross-repository acceptance, documentation, and Phase 2 gate

**Files:**
- Modify: `README.md`
- Modify: `TASKS.md`
- Modify: `docs/ROADMAP.md` only where implementation names or acceptance thresholds need precise wording.
- Create: `docs/acceptance/phase-2-rag-knowledge-pipeline.md`
- Modify: `tools/agent-evaluation/README.md` or create it if the evaluation package has no usage guide.

**Interfaces:**
- Consumes: Tasks 1–6, a clean Core worktree, Docker-backed MySQL/Qdrant integration tests, and the fixed sanitized RAG dataset.
- Produces: reproducible Phase 2 evidence and a clear Phase 3 entry gate.

- [x] **Step 1: Run the full Copilot verification**

Run: `./scripts/verify.ps1` from `C:\dev\worktrees\finguard-copilot-phase1`; then run the complete Phase 2 Java suite with Docker-backed Testcontainers and the Python RAG suite. Expected: zero failures, errors, and skipped tests.

- [x] **Step 2: Run the knowledge acceptance scenario**

Register two sanitized policy documents, build candidate version `v1`, evaluate and publish it, run retrieval for a fixed query, create an analysis citation, update one document, delete another, publish `v2`, verify old content is absent, then roll back to `v1` and verify exact restoration.

- [x] **Step 3: Prove security and data boundaries**

Compare the vector payload and metadata against an allowlist; assert no transaction ID, amount, review status, JWT, secret, raw CSV, prompt, or arbitrary URL is present. Assert that the Core client still exposes only the two fixed GET paths and that no Core business table changes during the acceptance run.

- [x] **Step 4: Clean acceptance resources and inspect Git**

Remove only isolated MySQL/Qdrant/Testcontainers resources created for the acceptance run, verify application ports and containers are released, run `git diff --check`, and confirm only planned Phase 2 files changed.

- [x] **Step 5: Update status honestly**

Check exactly the eight Phase 2 items in `TASKS.md` only after their direct evidence exists. Update `README.md` to distinguish Phase 2 RAG completion from Phase 3 real-model/context engineering and later Agent/UI/deployment work. Record the current Docker requirement as a reproducibility prerequisite, not as a skipped test.

- [ ] **Step 6: Commit only after explicit authorization**

Run: `git add README.md TASKS.md docs/ROADMAP.md docs/acceptance/phase-2-rag-knowledge-pipeline.md tools/agent-evaluation && git commit -m "docs: close phase two rag pipeline"` only after the user authorizes the commit. Do not push automatically.

## Phase 2 Acceptance Gate

- All eight Phase 2 checklist items have direct current evidence.
- A document is canonicalized, sanitized, checksummed, chunked deterministically, deduplicated, and stored with a source/version identity.
- Embeddings and Qdrant collections are versioned; runtime searches only the one published index version.
- Candidate evaluation, publication, incremental update, deletion, and rollback are atomic and failure-isolated.
- Every returned policy conclusion can be traced to a retrieved document/version/chunk/checksum citation; fabricated or stale citations fail closed.
- The fixed RAG dataset has reproducible metrics and is fully sanitized; the evaluator does not access production databases.
- Dynamic Core facts are absent from vector payloads and metadata, and Copilot still makes no Core write request.
- Full Java/Python/frontend verification, Docker-backed MySQL/Qdrant integration tests, cleanup checks, secret scans, and `git diff --check` pass with no skipped acceptance tests.
- Phase 3 begins only after this gate: real model adapter, context budget, Golden Dataset model comparison, token/latency/cost metrics, and explicit model profile.

## Suggested Execution Order

1. Task 1: domain and persistence contracts.
2. Task 2: deterministic ingestion and dynamic-fact rejection.
3. Task 3: fixed embeddings and Qdrant adapter.
4. Task 4: candidate build, fixed evaluation, and atomic publication.
5. Task 5: incremental update, deletion, and rollback.
6. Task 6: retrieval citations and safe analysis integration.
7. Task 7: cross-repository acceptance and documentation closure.

Do not begin Phase 3 until every Phase 2 acceptance-gate item has current evidence.
