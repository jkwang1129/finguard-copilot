# Dependency Baseline

Phase 0 pins the smallest reproducible toolchain needed to prove the three project boundaries: the Java production runtime, the Python offline evaluation toolkit, and the React demonstration client.

| Component | Phase 0 pin | Reason |
|---|---:|---|
| Java | 17 | Project runtime contract and Spring AI Alibaba baseline |
| Maven Wrapper | 3.9.16 | Reproducible local and CI builds |
| Spring Boot | 3.5.8 | Version declared by Spring AI Alibaba 1.1.2.2 |
| Spring AI Alibaba BOM | 1.1.2.2 | Current verified official release selected for the project |
| Spring AI | 1.1.2 | Managed by the Alibaba BOM |
| Springdoc | 2.8.8 | Version declared by the selected Alibaba baseline |
| MySQL | 8.4.10 | Same LTS image line used by FinGuard Core |
| Python | 3.12 | Evaluation-tool contract; managed by uv |
| Node.js | 24 | Local and CI runtime satisfying current Vite requirements |

The Spring AI Alibaba BOM is imported during Phase 0 so later Agent dependencies share one managed version set. Model, Graph, Qdrant, Redis, RabbitMQ, and Langfuse artifacts are intentionally added only with their first executable behavior. This keeps the foundation honest and prevents unused infrastructure from being presented as implemented capability.

Runtime credentials are environment-only. Local `.env` files, model keys, JWTs, and database passwords must never be committed.

P4-3 adds `spring-boot-starter-data-redis` for executable quota/lease control. With the existing Spring Boot 3.5.8 dependency management, the packaged runtime resolves Spring Data Redis 3.5.6 and Lettuce 6.6.0.RELEASE. Isolated tests use Redis 8.2.8-alpine; no new model/Agent framework dependency was added.
