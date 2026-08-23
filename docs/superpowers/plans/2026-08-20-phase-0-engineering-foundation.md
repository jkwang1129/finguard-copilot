# Phase 0 Engineering Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize a tested monorepo foundation containing the Java 17/Spring Boot production service, Python 3.12 evaluation toolkit, React/TypeScript demo UI, and CI verification without implementing review, RAG, or Agent business behavior.

**Architecture:** The repository root is the only production backend and Maven project. `tools/agent-evaluation` is an offline Python package that consumes public API/export contracts later; `frontend` is a Vite React client that will consume validated API/SSE events later. Phase 0 proves the toolchains, database migrations, test boundaries, and build pipeline while deferring all business integrations to their roadmap phases.

**Tech Stack:** Java 17, Maven 3.9.16 Wrapper, Spring Boot 3.5.8, Spring AI Alibaba BOM 1.1.2.2, Springdoc 2.8.8, MySQL 8.4.10, Flyway, Testcontainers, Python 3.12, uv, Pydantic, Pandas, pytest, Node.js 24, React, TypeScript, Vite, Vitest, Testing Library, GitHub Actions.

**Spec:** `docs/ARCHITECTURE.md`

## Global Constraints

- Production backend source lives under `src/main/java/com/finguard/copilot`; Python and frontend code never become backend runtime dependencies.
- Compile and test Java with release 17 even when another JDK is installed.
- Use Spring Boot 3.5.8 because Spring AI Alibaba 1.1.2.2 declares that tested baseline; import the Alibaba BOM now but add model/Graph starters only in the phase that uses them.
- Use Maven Wrapper 3.9.16 and MySQL image `mysql:8.4.10`.
- Python requires `>=3.12,<3.13`; `uv` manages it without changing the system Python.
- Node.js 24 is the project CI version and satisfies Vite's Node.js requirement.
- Never persist JWTs, model keys, database passwords, `.env` files, generated datasets, build output, or local virtual environments.
- Phase 0 creates no Core client, model call, RAG pipeline, Tool, Graph, MCP, Redis, RabbitMQ, Langfuse, or business endpoint.
- Every test must execute; do not hide failures with disabled tests, ignored tests, or Docker-absence skips.
- Commit commands are documented for future execution but must not be run until the user explicitly authorizes a commit.

---

## Planned File Map

```text
.editorconfig                              shared whitespace rules
.gitattributes                             LF/text normalization policy
.gitignore                                 Java/Python/Node/IDE/secret exclusions
.github/workflows/ci.yml                   independent Java, Python, frontend, secret-scan jobs
pom.xml                                    root Spring Boot build and dependency baseline
.mvn/wrapper/maven-wrapper.properties      Maven 3.9.16 distribution pin
mvnw, mvnw.cmd                             cross-platform Maven entrypoints
src/main/java/com/finguard/copilot/
  FinGuardCopilotApplication.java          application entrypoint
  config/OpenApiConfiguration.java         API title/version metadata
src/main/resources/
  application.yml                          runtime configuration and safe management exposure
  db/migration/V1__create_analysis_run.sql first immutable operational migration
src/test/java/com/finguard/copilot/
  FinGuardCopilotApplicationTests.java     real MySQL context/health/OpenAPI smoke tests
  MigrationTest.java                       verifies Flyway V1 schema
tools/agent-evaluation/
  .python-version                          Python 3.12 pin
  pyproject.toml                           Python package and test dependencies
  uv.lock                                  reproducible dependency lock
  src/finguard_agent_evaluation/
    __init__.py                            public package version
    models.py                              first shared evaluation case contract
    dataset.py                             JSONL loader with Pydantic validation
  tests/test_dataset.py                    valid and invalid dataset tests
frontend/
  package.json, package-lock.json          Node scripts and exact dependency lock
  vite.config.ts                           React and Vitest configuration
  src/App.tsx                              foundation status page
  src/App.test.tsx                         UI smoke test
  src/test/setup.ts                        jest-dom registration
scripts/verify.ps1                         Windows full local verification
scripts/verify.sh                          Linux/CI-equivalent local verification
docs/DEPENDENCIES.md                       exact phase-0 pins and official compatibility rationale
```

### Task 1: Repository and dependency baseline

**Files:**
- Create: `.editorconfig`
- Create: `.gitattributes`
- Create: `.gitignore`
- Create: `docs/DEPENDENCIES.md`

**Interfaces:**
- Consumes: `docs/ARCHITECTURE.md` global boundaries.
- Produces: exact runtime pins and repository exclusions used by every later task.

- [ ] **Step 1: Write repository hygiene files**

Create `.editorconfig` with UTF-8, LF, final newline, four-space Java/Python/YAML indentation, and two-space JSON/TypeScript indentation. Create `.gitattributes` with `* text=auto eol=lf` plus binary rules for common images and archives. Create `.gitignore` covering `target/`, `.mvn/timing.properties`, `.venv/`, `__pycache__/`, `.pytest_cache/`, `.uv-cache/`, `node_modules/`, `dist/`, `.idea/`, `.vscode/`, `.env*`, `!.env.example`, logs, keys, certificates, and generated evaluation reports.

- [ ] **Step 2: Record exact dependency decisions**

Create `docs/DEPENDENCIES.md` with this compatibility table:

```markdown
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
| Node.js | 24 | Local/CI runtime satisfying current Vite requirements |
```

State explicitly that model, Qdrant, Redis, RabbitMQ, Langfuse, and Graph artifacts are added only when their first executable behavior is implemented.

- [ ] **Step 3: Verify hygiene rules**

Run:

```powershell
git check-ignore -v .env target/classes/App.class frontend/node_modules/x tools/agent-evaluation/.venv/pyvenv.cfg
git status --short
```

Expected: each synthetic path is matched by `.gitignore`; only intended source and documentation files remain visible.

- [ ] **Step 4: Commit after authorization**

```powershell
git add .editorconfig .gitattributes .gitignore docs/DEPENDENCIES.md
git commit -m "chore: establish repository dependency baseline"
```

### Task 2: Java Spring Boot application and observable API foundation

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `src/main/java/com/finguard/copilot/FinGuardCopilotApplication.java`
- Create: `src/main/java/com/finguard/copilot/config/OpenApiConfiguration.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/com/finguard/copilot/FinGuardCopilotApplicationTests.java`

**Interfaces:**
- Consumes: version pins from `docs/DEPENDENCIES.md`.
- Produces: `FinGuardCopilotApplication`, `/actuator/health`, `/actuator/prometheus`, and `/v3/api-docs`.

- [ ] **Step 1: Write the failing application smoke test**

Create a Spring Boot test using `@SpringBootTest`, `@AutoConfigureMockMvc`, Testcontainers MySQL, and `@ServiceConnection`. The test class must contain:

```java
@Test
void exposesHealthAndOpenApi() throws Exception {
    mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("FinGuard Copilot API"));
}
```

Use `new MySQLContainer<>("mysql:8.4.10")` as the static container.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
mvn -B -ntp -Dtest=FinGuardCopilotApplicationTests test
```

Expected: FAIL because the Maven project and application classes do not exist.

- [ ] **Step 3: Create the minimal Maven build**

Create `pom.xml` with parent `org.springframework.boot:spring-boot-starter-parent:3.5.8`, Java 17, Springdoc 2.8.8, and imported BOM `com.alibaba.cloud.ai:spring-ai-alibaba-bom:1.1.2.2`. Add only these dependencies:

```text
spring-boot-starter-web
spring-boot-starter-validation
spring-boot-starter-jdbc
spring-boot-starter-actuator
micrometer-registry-prometheus
springdoc-openapi-starter-webmvc-ui
flyway-core
flyway-mysql
mysql-connector-j (runtime)
spring-boot-starter-test (test)
spring-boot-testcontainers (test)
org.testcontainers:junit-jupiter (test)
org.testcontainers:mysql (test)
```

Configure `maven-compiler-plugin` release 17 and `spring-boot-maven-plugin`.

- [ ] **Step 4: Add the application and OpenAPI configuration**

Create the application entrypoint:

```java
package com.finguard.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinGuardCopilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinGuardCopilotApplication.class, args);
    }
}
```

Create an `OpenAPI` bean whose title is `FinGuard Copilot API`, version is `v1`, and description is `Read-only AI-assisted review analysis API`.

- [ ] **Step 5: Add safe runtime configuration**

Create `application.yml` with application name `finguard-copilot`, environment-only database URL/username/password, Flyway enabled, `open-in-view: false`, graceful shutdown, and management exposure limited to `health,prometheus`. Never provide a default database password.

- [ ] **Step 6: Generate and verify Maven Wrapper**

Run:

```powershell
mvn -N wrapper:wrapper -Dmaven=3.9.16
.\mvnw.cmd -version
```

Expected: wrapper reports Maven 3.9.16 and Java 17.

- [ ] **Step 7: Run the smoke test**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=FinGuardCopilotApplicationTests test
```

Expected: PASS with real MySQL container, health `UP`, and OpenAPI title assertion passing.

- [ ] **Step 8: Commit after authorization**

```powershell
git add pom.xml .mvn mvnw mvnw.cmd src/main src/test/java/com/finguard/copilot/FinGuardCopilotApplicationTests.java
git commit -m "feat: initialize copilot backend foundation"
```

### Task 3: Flyway operational schema baseline

**Files:**
- Create: `src/main/resources/db/migration/V1__create_analysis_run.sql`
- Test: `src/test/java/com/finguard/copilot/MigrationTest.java`

**Interfaces:**
- Consumes: MySQL Testcontainer and Spring datasource from Task 2.
- Produces: immutable V1 `analysis_run` table contract for Phase 1.

- [ ] **Step 1: Write the failing migration test**

Create a Spring Boot/Testcontainers test that queries `information_schema.tables` and asserts exactly one table named `analysis_run` exists in the active database. Query `information_schema.columns` and assert the required columns are:

```text
id
status
reviewer_username
review_task_id
review_task_version
idempotency_key
request_hash
created_at
updated_at
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=MigrationTest test
```

Expected: FAIL because `analysis_run` does not exist.

- [ ] **Step 3: Add the immutable V1 migration**

Create `V1__create_analysis_run.sql` with `BINARY(16)` primary key, bounded status/username/idempotency fields, unsigned task ID/version, SHA-256 request hash, microsecond timestamps, a unique key on `(reviewer_username, idempotency_key)`, and an index on `(review_task_id, review_task_version)`. Use `InnoDB` and `utf8mb4`.

- [ ] **Step 4: Run migration and application tests**

Run:

```powershell
.\mvnw.cmd -B -ntp -Dtest=MigrationTest,FinGuardCopilotApplicationTests test
```

Expected: both tests PASS and Flyway reports schema version 1.

- [ ] **Step 5: Commit after authorization**

```powershell
git add src/main/resources/db/migration/V1__create_analysis_run.sql src/test/java/com/finguard/copilot/MigrationTest.java
git commit -m "feat: add analysis run schema baseline"
```

### Task 4: Python evaluation toolkit foundation

**Files:**
- Create: `tools/agent-evaluation/.python-version`
- Create: `tools/agent-evaluation/pyproject.toml`
- Create: `tools/agent-evaluation/uv.lock`
- Create: `tools/agent-evaluation/src/finguard_agent_evaluation/__init__.py`
- Create: `tools/agent-evaluation/src/finguard_agent_evaluation/models.py`
- Create: `tools/agent-evaluation/src/finguard_agent_evaluation/dataset.py`
- Test: `tools/agent-evaluation/tests/test_dataset.py`

**Interfaces:**
- Consumes: no backend internals; JSONL is the only input.
- Produces: `Recommendation`, `EvaluationCase`, and `load_cases(path: Path) -> list[EvaluationCase]`.

- [ ] **Step 1: Write failing dataset tests**

Write one test that loads a temporary JSONL file containing:

```json
{"case_id":"high-amount-001","review_task_id":1001,"expected_recommendation":"CONFIRMED"}
```

Assert `load_cases` returns one typed case. Write a second test whose recommendation is `AUTO_APPROVE` and assert Pydantic raises `ValidationError`.

- [ ] **Step 2: Run tests and verify import failure**

Run from `tools/agent-evaluation`:

```powershell
uv run --python 3.12 pytest -q
```

Expected: FAIL because the package does not exist; `uv` installs a managed CPython 3.12 if required without changing the system Python.

- [ ] **Step 3: Create the package metadata and exact contract**

Use `requires-python = ">=3.12,<3.13"`, Hatchling build backend, runtime dependencies Pydantic 2.x and Pandas 2.x, and pytest 8.x in the dev dependency group. Define:

```python
Recommendation = Literal["CONFIRMED", "IGNORED", "INSUFFICIENT_EVIDENCE"]

class EvaluationCase(BaseModel):
    case_id: str = Field(min_length=1, max_length=100)
    review_task_id: int = Field(gt=0)
    expected_recommendation: Recommendation
```

Implement `load_cases` with `pandas.read_json(path, lines=True)` and `EvaluationCase.model_validate` for every record.

- [ ] **Step 4: Lock dependencies and run tests**

Run:

```powershell
uv lock --python 3.12
uv sync --locked --python 3.12
uv run --python 3.12 pytest -q
```

Expected: two tests PASS and `uv.lock` is created.

- [ ] **Step 5: Commit after authorization**

```powershell
git add tools/agent-evaluation
git commit -m "feat: initialize agent evaluation toolkit"
```

### Task 5: React and TypeScript demo foundation

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/package-lock.json`
- Create: `frontend/vite.config.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.css`
- Create: `frontend/src/App.test.tsx`
- Create: `frontend/src/test/setup.ts`

**Interfaces:**
- Consumes: no backend API in Phase 0.
- Produces: tested static foundation page and `npm run test`, `npm run lint`, `npm run build` scripts.

- [ ] **Step 1: Scaffold the official React TypeScript template**

Run from the repository root:

```powershell
npm create vite@latest frontend -- --template react-ts --no-interactive
Set-Location frontend
npm install
npm install --save-dev vitest jsdom @testing-library/react @testing-library/jest-dom
```

- [ ] **Step 2: Write the failing UI test**

Configure Vitest with `jsdom` and `src/test/setup.ts`. Add a test that renders `<App />` and asserts an accessible level-one heading named `FinGuard Copilot` and text `Engineering foundation ready` are visible.

- [ ] **Step 3: Run the test and verify it fails**

Run:

```powershell
npm run test -- --run
```

Expected: FAIL because the default Vite page does not contain the required status text.

- [ ] **Step 4: Implement the minimal foundation page**

Replace the default counter page with a semantic `main` containing the project heading, the status text, and a three-item list naming `Java Agent Runtime`, `Python Evaluation`, and `Validated React UI`. Remove unused Vite logo assets and default counter state.

- [ ] **Step 5: Run frontend verification**

Run:

```powershell
npm run lint
npm run test -- --run
npm run build
```

Expected: lint, test, TypeScript compilation, and production build all PASS.

- [ ] **Step 6: Commit after authorization**

```powershell
git add frontend
git commit -m "feat: initialize copilot demo frontend"
```

### Task 6: Unified verification and CI

**Files:**
- Create: `scripts/verify.ps1`
- Create: `scripts/verify.sh`
- Create: `.github/workflows/ci.yml`
- Modify: `README.md`
- Modify: `TASKS.md`

**Interfaces:**
- Consumes: Java, Python, and frontend commands from Tasks 2-5.
- Produces: one local verification entrypoint per OS and four independent CI jobs.

- [ ] **Step 1: Write local verification scripts**

Both scripts must stop on the first failure and run, in order:

```text
Maven clean test
Python uv sync --locked and pytest
Frontend npm ci, lint, test, and build
git diff --check
```

The PowerShell script uses `Push-Location`/`Pop-Location`; the shell script uses subshells so a failed directory change cannot leak into later steps.

- [ ] **Step 2: Create CI jobs**

Create `.github/workflows/ci.yml` triggered on pushes to `main` and pull requests. Set `permissions: contents: read`. Define independent jobs:

```text
java: actions/setup-java@v4, distribution temurin, java-version 17, ./mvnw -B -ntp clean test
python: astral-sh/setup-uv@v6, Python 3.12, uv sync --locked, uv run pytest
frontend: actions/setup-node@v4, Node 24, npm ci, npm run lint, npm run test -- --run, npm run build
secret-scan: gitleaks/gitleaks-action@v2
```

- [ ] **Step 3: Run full local verification**

Run:

```powershell
.\scripts\verify.ps1
```

Expected: Java tests, Python tests, frontend lint/tests/build, and whitespace checks all PASS with no skipped tests.

- [ ] **Step 4: Update project status**

Update `README.md` to state that the Phase 0 engineering foundation exists and document the three verification commands. In `TASKS.md`, check only tasks proven by the current output: compatibility pins, Maven initialization, OpenAPI/config, MySQL/Flyway/Testcontainers, Java/Python/React boundaries, CI, and normal tests. Leave model ports, Stub model, RAG, Agent, and production deployment unchecked.

- [ ] **Step 5: Audit repository state**

Run:

```powershell
git status --short
git diff --check
rg -n -i "password\s*[:=]|api[_-]?key\s*[:=]|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY" -g '!*.md' -g '!package-lock.json' -g '!uv.lock'
```

Expected: only planned source files are present, whitespace check is clean, and secret scan returns no source matches.

- [ ] **Step 6: Commit after authorization**

```powershell
git add .github scripts README.md TASKS.md
git commit -m "ci: verify multi-language project foundation"
```

## Phase 0 Acceptance

Phase 0 is accepted only when all of the following are true:

- Maven Wrapper compiles with Java release 17 and all Java tests pass against real MySQL 8.4.10.
- Flyway applies immutable V1 and the schema test verifies required columns.
- Python 3.12 is managed by uv, the lock file is present, and all evaluation tests pass.
- React/TypeScript lint, Vitest, and Vite production build pass under Node 24.
- CI defines independent Java, Python, frontend, and secret-scan jobs.
- No model key, JWT, password, `.env`, local virtual environment, Node modules, or build output is tracked.
- No Core integration, model call, RAG, Tool, Graph, MCP, Redis, RabbitMQ, or Langfuse runtime behavior is falsely claimed as implemented.
