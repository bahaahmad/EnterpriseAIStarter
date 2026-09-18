# rag-service — developer guide

Spring Boot service (Java 21) with two jobs: **ingest** documents into the knowledge base, and **answer** questions
from it with permission-aware retrieval. Building block A5 of the blueprint.

> Package is currently `com.cuenterprise.ai.rag`; it moves to `com.cuenterprise.ai.rag` in the next code update.

See also: `../docs/phase0-flows.md` (diagrams), `../requirements.md` (REQ-A5-*), `../SETUP.md` (how to run).

## 1. Structure

| Package | Class | Responsibility |
|---|---|---|
| (root) | `RagServiceApplication` | Starts Spring and loads the settings |
| `config` | `RagProperties` | All `rag.*` settings from `application.yml` as one Java record |
| `api` | `ChatController` | Question endpoint: `/v1/chat/completions`, `/v1/models` |
| `api` | `IngestController` | Ingest endpoint: `/admin/ingest` |
| `identity` | `UserDirectory` | Email → groups, from `users.yaml` |
| `gateway` | `GatewayClient` | The only way out to the models: `embed()` and `chat()` through LiteLLM |
| `ingest` | `IngestService` | Runs ingestion: manifest → Docling → chunk → embed → store |
| `ingest` | `DoclingClient` | Calls the Docling container |
| `ingest` | `Chunker` | Splits markdown into chunks (plain Java; unit-tested) |
| `retrieval` | `ChunkRepository` | The only SQL: `hybridSearch()` and `replaceDocument()` |
| `retrieval` | `RetrievedChunk` | One search result, as a record |

**Design rule: each external system has exactly one class that talks to it.**

| External system | Its single class |
|---|---|
| LiteLLM gateway | `GatewayClient` |
| Docling | `DoclingClient` |
| Postgres / pgvector | `ChunkRepository` |
| `users.yaml` | `UserDirectory` |

Moving to the server changes one class per system — e.g. Keycloak replaces the internals of `UserDirectory` only.

## 2. Startup

1. Spring reads `src/main/resources/application.yml`, which imports the project `.env` as properties
   (`spring.config.import: optional:file:../.env[.properties]`). Real environment variables take precedence —
   that is how the container overrides host-only values.
2. `RagProperties` is filled in.
3. The database connection pool is created.
4. `UserDirectory` reads `users.yaml`. **If the file is missing, startup fails** — intended: no identity, no service.
5. Listens on port **8081**; health at `/actuator/health`; traces exported to `RAG_OTLP_ENDPOINT` (Phoenix).

### Environment variables (all in the project `.env`)

| Variable | Laptop value | In the container | Used for |
|---|---|---|---|
| `RAG_DB_URL` | `jdbc:postgresql://localhost:5432/kb` | `…//postgres:5432/kb` | Knowledge base |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres` / `postgres_pw` | same | DB login |
| `RAG_GATEWAY_URL` | `http://localhost:4000/v1` | `http://litellm:4000/v1` | LiteLLM |
| `RAG_GATEWAY_KEY` | `sk-rag-gateway-key` | same | Outbound calls to the gateway (scoped key) |
| `RAG_SERVICE_KEY` | `sk-rag-service-key` | same | Callers must present it (inbound) |
| `RAG_DOCLING_URL` | `http://localhost:5010` | `http://docling:5001` | Docling |
| `RAG_CORPUS_DIR` | `../data/corpus` | `/data/corpus` | Manifest and documents |
| `RAG_USERS_FILE` | `../config/identity/users.yaml` | `/config/identity/users.yaml` | User → groups |
| `RAG_OTLP_ENDPOINT` | `http://localhost:6006/v1/traces` | `http://phoenix:6006/v1/traces` | Tracing |

The run configuration `2 rag-service` sets only the main class, `-Xmx512m` and the working directory
(`rag-service/`, which the `../` paths rely on).

Tuning in `application.yml`: `top-k` (6), `chunk-chars` (1200), `chunk-overlap` (150),
`prompt-suffix` (`/no_think`, Qwen3-specific), `user-header` (`X-OpenWebUI-User-Email`), `docling-path`.

## 3. Question flow — `ChatController.chat`

1. **Authenticate the caller:** bearer token must equal `RAG_SERVICE_KEY`, otherwise 401.
2. **Identify the user:** read the email header → `UserDirectory.groupsFor()`. Unknown or missing → no groups.
3. **Extract the question:** the last `user` message in the request.
4. **Retrieve:** `GatewayClient.embed()` → `ChunkRepository.hybridSearch()` (ACL filter inside the SQL).
5. **Decide:** no chunks → return the fixed answer *"I can't find this in the knowledge available to you."*
   and **skip the model**.
6. **Augment:** system rules + numbered sources `[1]…[n]` + `prompt-suffix`.
7. **Generate:** `GatewayClient.chat()` with `user` = email, for per-user attribution in the gateway.
8. **Respond in OpenAI format:**
   - `stream: true` → one SSE event, then `[DONE]` (Open WebUI);
   - `stream: false` → JSON with an extra `citations` array (eval harness).

### Endpoints

| Method | Path | Caller | Auth |
|---|---|---|---|
| `POST` | `/v1/chat/completions` | LiteLLM (model `kb-copilot`), eval harness | Bearer `RAG_SERVICE_KEY` + user email header |
| `GET` | `/v1/models` | LiteLLM | none |
| `POST` | `/admin/ingest` | You (run config *3 Ingest corpus*) | **none — localhost only** |
| `GET` | `/actuator/health` | You, Docker | none |

Example calls: `../requests.http` (environment `local`).

## 4. Ingest flow — `IngestService.ingestManifest`

1. Read `manifest.yaml`. For each document, **reject it if it has no allowed groups**.
2. Convert the file with Docling → markdown.
3. `Chunker`: split on headings, ≤ `chunk-chars` with `chunk-overlap`, each chunk prefixed with
   `Title > Heading`.
4. Embed in batches of 16 via the gateway (`embed-default` = bge-m3, 1024 dimensions).
5. `ChunkRepository.replaceDocument()`: delete the document's rows and insert the new ones **in one transaction**.
6. Return a map of document ID → chunk count.

## 5. Retrieval — `ChunkRepository.hybridSearch`

- Empty groups → return nothing, SQL not executed (fail closed).
- One SQL statement with two candidate sets, **both filtered by `allowed_groups && groups`**:
  - vector search: cosine distance, top 40 (HNSW index);
  - keyword search: Postgres full text, top 40 (GIN index).
- Merged with Reciprocal Rank Fusion: `1/(60+rank_vector) + 1/(60+rank_keyword)`, top `k`.
- `SET LOCAL hnsw.iterative_scan = relaxed_order` keeps recall when the ACL filter removes most index candidates
  (needs pgvector ≥ 0.8).

## 6. Deliberate simplifications

| Simplification | Consequence | Later |
|---|---|---|
| No framework (LangChain4j / Spring AI) | Every call is plain HTTP or SQL you can read | Optional, behind the same API |
| Fake streaming | Full answer arrives as one event | Token streaming |
| Only the last user message is used | No conversation memory; "and for VIP?" loses context | Query rewriting with history |
| No retries, timeouts or circuit breakers on outbound calls | A slow model or Docling hangs the request | Resilience4j, explicit timeouts |
| `/admin/ingest` unauthenticated | Anyone reaching the port can re-ingest | Separate job and admin role |
| Identity from a plain header | Anyone reaching rag-service can claim any user | Keycloak JWT on the server |
| Ingest and search share the `postgres` login | Search path could write | `rag_reader` role on the server |
| No reranker | Ranking is RRF only | TEI reranker (`rerank` profile) |

## 7. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Every answer is "I can't find this…" | User email header not arriving (check the log for the email) or the KB is empty (`SELECT count(*) FROM kb_chunk`) |
| 401 from rag-service | LiteLLM container started before `RAG_SERVICE_KEY` changed — restart litellm |
| 401 / 403 from LiteLLM | Scoped keys missing after `down -v` — rerun `scripts/create-keys.sh` |
| Startup fails on `users.yaml`, or `Could not resolve placeholder` | Working directory not `rag-service/`, so `../.env` and `../config` are not found |
| Values in `.env` behave oddly | Inline comment or quotes on the line — Spring keeps them as part of the value |
| Ingest fails at Docling | Docling not up (`ingest` profile), or `docling-path` wrong for the image version |
| `unrecognized configuration parameter "hnsw.iterative_scan"` | pgvector older than 0.8 |
| `expected 1024 dimensions` | Embedding model changed without re-creating the table and re-ingesting |
| Answers contain `<think>` text | `prompt-suffix` removed, or a non-Qwen3 model ignoring it |
| Requests hang, then time out | Ollama out of memory or swapping — `docker stats` |
