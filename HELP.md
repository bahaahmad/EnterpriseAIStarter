# EnterpriseAIStarter — setup and operation

Everything needed to install, run and troubleshoot the Starter PoC on one machine.
Service internals: `rag-service/HELP.md`. What the PoC set out to prove: `README.md`.

---

## 1. What runs

| Component | Role | On a GPU server |
|---|---|---|
| **Open WebUI** | Chat UI; talks only to the gateway | Same |
| **LiteLLM** | Gateway: keys, quotas, logical model names, PII guardrail, per-user spend | Same, 2+ replicas |
| **rag-service** (Java) | Retrieve, augment, generate; owns identity and permission filtering | Same |
| **Ollama** | Model server, CPU | Replaced by **vLLM** on GPUs |
| **Qwen3 4B / 8B** | Answers, and the evaluation judge | 70B-class at FP8 |
| **bge-m3** | Embeddings: text to 1,024 numbers | Same |
| **PostgreSQL + pgvector** | `kb_chunk`: text, vector, ACLs; also the gateway's key and spend database | Same, separated |
| **Docling** | File to markdown, including OCR | Same |
| **Microsoft Presidio** | PII detection and masking, called by the gateway | Same, plus local recognizers |
| **Arize Phoenix** | Traces: prompts, retrieved chunks, responses | Replaced by **Langfuse** |
| **Eval harness** (Python) | Golden-set scoring and the leakage gate | Same, plus Ragas |

Two rules the design rests on: every model call goes through the gateway, and the model never decides what a
user may see — permission filtering happens in SQL, before the model is involved.

---

## 2. Machine prerequisites

Built and run on Windows 11, 16 GB RAM, 8 cores, **no GPU**. Slow is expected; correctness is not negotiable.

| Item | Setting |
|---|---|
| Docker | Docker Desktop, WSL2 backend |
| WSL2 memory | `%UserProfile%\.wslconfig` → see below, then `wsl --shutdown` |
| Models | Ollama **in Docker**, CPU: `qwen3:4b` answerer, `qwen3:8b` judge, `bge-m3` embeddings |
| JDK | Temurin 21 |
| Python | 3.11+ (`py -3.11`) |
| Shell | Git for Windows (Git Bash) — run configurations and scripts use it |
| Scanning | Nothing to install: Trivy runs as a container |

```ini
# %UserProfile%\.wslconfig
[wsl2]
memory=10GB
processors=7
swap=8GB
```

Memory is the binding constraint: containers total roughly 10.5 GB with everything up, Windows and IntelliJ
take 5–6 GB. That is why stages exist and why the UI and Docling stop during an evaluation run. Expect swap;
slow is accepted, OOM-killed is not — check `docker stats` when something "hangs".

**Before cloning:** `git config --global core.autocrlf input`. `.gitattributes` forces LF on scripts, SQL and
YAML; a CRLF shell script fails inside a container with a confusing "not found" error.

---

## 3. Project layout

```
EnterpriseAIStarter/         (Docker Compose project name stays ai-poc)
├─ docker-compose.yml        every container; values come from .env
├─ .env                      single source of all settings and passwords
├─ config/litellm/           gateway: stage1 (plain) and full (Presidio + tracing) configs
├─ config/identity/          user -> groups (stand-in for Keycloak)
├─ db/init.sql, init.sh      kb_chunk schema: ACL array, HNSW and full-text indexes
├─ rag-service/              Java 21 / Spring Boot; OpenAI-compatible API "kb-copilot"
├─ eval/                     Python harness: golden-set scoring, leakage gate
├─ data/corpus/              synthetic documents + manifest with ACLs
├─ data/golden/              golden set including leakage and injection cases
├─ models/registry.yaml      model digests, licence, approver
├─ scripts/                  stage, pull, smoke-test, keys, pin, scan
├─ .run/                     shared IntelliJ run configurations
├─ requests.http             HTTP client smoke tests (+ http-client.env.json)
└─ HELP.md, README.md        this file, and what the PoC proves
```

---

## 4. IntelliJ

1. **Open** the `EnterpriseAIStarter` folder. Trust the project.
2. **JDK**: Project Structure → SDK → Temurin 21, language level 21.
3. **Maven**: right-click `rag-service/pom.xml` → *Add as Maven Project*.
4. **Plugins**: Docker (bundled), Python, .env files support. Ultimate adds Spring, Database Tools, HTTP Client.
5. **Terminal**: Settings → Tools → Terminal → Shell path `C:\Program Files\Git\bin\bash.exe`.
6. **Python**: `cd eval && py -3.11 -m venv .venv && .venv/Scripts/pip install -e .`, then add `eval` as a
   Python module with that interpreter and mark `eval/src` as Sources Root.
7. **Docker**: Settings → Docker → Docker for Windows. Services window (Alt+8) shows containers and logs.
8. **Database**: PostgreSQL → `localhost:5432`, db `kb`, user `postgres`, password `postgres_pw`.
9. **Run configurations** load from `.run/`. If Git is installed elsewhere, fix the interpreter path in each.

| Configuration | Does |
|---|---|
| `1 stage: dev` … `4 stage: eval` | Bring-up stages (§6) |
| `2 rag-service` | Spring Boot, `-Xmx512m`, settings from `.env` |
| `Ingest corpus` | `POST /admin/ingest` |
| `Eval: golden set` | Run the scoring harness (all cases) |
| `Eval: leakage gate` | Leakage and refusal cases only — minutes, no judge |
| `smoke-test-01…05` | Gateway checks (§7) |
| `stage: status` / `stage: down` | Where am I / stop everything |

---

## 5. First run

```bash
cp .env.example .env               # if you do not have one yet
./scripts/pull-models.sh           # all images + models, writes models/registry.yaml
./scripts/stage.sh dev             # core services
./scripts/create-keys.sh           # scoped gateway keys, with the fixed values in .env
./scripts/smoke-test.sh all        # models, embeddings, one answer
```

Then run `2 rag-service` in IntelliJ and check `http://localhost:8081/actuator/health`.

**Open WebUI** at `localhost:3000`: the first account created is the admin. Create the three test users with
the emails in `config/identity/users.yaml`, make the `kb-copilot` model public in Admin Panel → Settings →
Models, and turn off follow-up suggestions, title generation and tags in Admin Panel → Settings → Interface —
each of those fires its own model call and costs 30 s+ per message on CPU.

---

## 6. Stages

`scripts/stage.sh` sets the values in `.env` that a stage needs, then starts exactly the right services. One
source of truth; no second compose file to drift.

| Stage | Brings up | What it is for | Done when |
|---|---|---|---|
| `dev` | postgres, ollama, litellm, open-webui | Plumbing: UI → gateway → rag-service → model | A question reaches the model and the rag-service log shows the user's email |
| `ingest` | + docling | Corpus into pgvector | `/admin/ingest` returns a chunk count per document |
| `full` | + presidio, phoenix; `config.full.yaml` | Guardrails and tracing | A question with a name in it is masked, and the trace shows every hop |
| `eval` | full, minus open-webui and docling; Ollama at 7g; 8B judge | Measuring | Golden set completes with zero leakage |

```bash
./scripts/stage.sh dev|ingest|full|eval
./scripts/stage.sh status        # what .env says, what is running
./scripts/stage.sh down          # stop everything, keep all data
```

Ingest requires Docling, so run `stage.sh ingest` before `/admin/ingest`; `full` stops Docling again.

---

## 7. Checking it works

```bash
./scripts/smoke-test.sh all       # model list, embedding dimensions, one answer, with timings
./scripts/smoke-test.sh watch     # docker stats, then the ollama log
```

Then `requests.http` (environment `local`): the junior question, the leakage probe, the senior question, a
wrong-key 401, and a check that the rag-service key cannot reach `kb-copilot`.

```sql
-- what is in the knowledge base
SELECT doc_id, count(*), min(array_to_string(allowed_groups,',')) FROM kb_chunk GROUP BY doc_id ORDER BY 1;
```

Traces: `http://localhost:6006`. Gateway keys and spend: `http://localhost:4000/ui`.

---

## 8. Request path

```
Open WebUI ──(X-OpenWebUI-User-Email)──► LiteLLM [Presidio pre-call, key, quota, log]
   ──► rag-service /v1/chat/completions  (model "kb-copilot")
         ├─ users.yaml → groups          (unknown user → no groups → no content)
         ├─ LiteLLM /embeddings (bge-m3)
         ├─ pgvector hybrid search, ACL filter inside the SQL
         └─ LiteLLM /chat/completions (chat-default) with numbered sources
   ◄── answer + citations ──► traces to Phoenix
eval ──► rag-service direct (per test user) + gateway judge model
```

A question that retrieves nothing permitted returns a fixed sentence and **never calls the model** — visible
in the trace as a retrieval span with no LLM span after it.

---

## 9. Models in Ollama

There is no registration step; a model exists once pulled, then it is addressable by name.

1. `ollama pull qwen3:4b` fetches a **manifest** from `registry.ollama.ai` and the **blobs** it lists: weights
   (GGUF), chat template, parameters, licence. Stored in the `ai-poc_ollama` volume under
   `/root/.ollama/models`, blobs named by sha256 digest.
2. Names are `family:tag`, the tag usually size and quantization. `ollama list` shows what you have;
   `ollama show qwen3:4b` shows parameters, context length and template.
3. Behaviour ships with the model: the manifest carries the chat template, which is why OpenAI-style messages
   are formatted correctly without us doing anything.
4. Loading is on demand — weights enter RAM on first use, stay for `OLLAMA_KEEP_ALIVE` (10m), then unload.
   The first request after a restart is slow because of that load. Normal, not a fault.
5. **LiteLLM maps logical to physical**: `chat-default` → `ollama_chat/qwen3:4b` in `config/litellm/*.yaml`.
   That mapping is the only place a physical model name appears, which is what makes the vLLM swap a config
   change.
6. `models/registry.yaml` (from `scripts/record-model-hashes.sh`) is the governance record: name, digest,
   size, licence, approver. Ollama's own store is a cache, not an approval record.

A tag is not immutable — record digests and compare them, or two evaluation runs are not comparable.

---

## 10. Keys and passwords

All in `.env`. Each identifies **one caller to one receiver**, so every hop is authorized and audited
separately. Keys identify *applications*; the *person* is identified by the email header, which is not a
credential — acceptable on an isolated laptop only, replaced by a Keycloak token on a server.

| Value | Presented by | To | Purpose |
|---|---|---|---|
| `sk-litellm-master` | You, scripts, eval | LiteLLM | Admin: create keys, see spend, any model |
| `sk-openwebui-key-01` | Open WebUI | LiteLLM | Scoped: `kb-copilot`, `chat-default` |
| `sk-rag-gateway-key` | rag-service | LiteLLM | Scoped: `chat-default`, `embed-default` (stops a loop through itself) |
| `sk-rag-service-key` | LiteLLM | rag-service | Proves the call came through the gateway |
| `postgres_pw` | LiteLLM, rag-service, you | Postgres | Database login |
| `rag_reader_pw` | rag-service (server) | Postgres | Read-only role for the query path |
| `keycloak_pw` / `webui_secret` | — | Keycloak / Open WebUI | Admin login; session cookie signing |

LiteLLM virtual keys must start with `sk-` and be at least 16 characters. Scoped keys live in LiteLLM's own
database — wipe `pgdata` and you must re-run `scripts/create-keys.sh`.

`.env` must stay plain `KEY=value` lines: no inline comments, no quotes. rag-service imports it as a
properties file through `spring.config.import`, and a trailing comment becomes part of the value.

---

## 11. Everyday commands

```bash
./scripts/stage.sh status              # where am I
docker stats                           # memory — first place to look when things "hang"
docker compose logs -f litellm         # gateway decisions, guardrail errors
docker compose logs -f ollama          # model load/unload
docker compose exec ollama ollama list # models present
docker compose exec ollama ollama ps   # what is loaded right now
./scripts/smoke-test.sh all            # gateway health
./scripts/scan-images.sh               # CVE scan, Trivy as a container
./scripts/pin-images.sh --write        # pin every image to its current digest
./scripts/stage.sh down                # stop, keep data
```

Never `docker compose down -v` unless you mean it: that deletes the knowledge base, the gateway keys, the UI
accounts, the traces **and** the downloaded models.

---

## 12. Troubleshooting

| Symptom | Cause |
|---|---|
| Every answer is "I can't find this…" | User email header not arriving, or the knowledge base is empty |
| 401 from rag-service | LiteLLM started before `RAG_SERVICE_KEY` changed — restart litellm |
| 401 / 403 from the gateway | Scoped keys missing after a `pgdata` wipe — re-run `create-keys.sh` |
| "Invalid HTTP request received" | Something speaking HTTP/2 to uvicorn; rag-service pins HTTP/1.1 in `HttpClientConfig` |
| Answers contain `<DATE_TIME>` | Presidio entity list too broad — see `pii_entities_config` in `config.full.yaml` |
| Guardrail seems not to run | `.env` has `LITELLM_CONFIG=config.stage1.yaml`, which has no guardrails |
| Ingest fails at the Docling step | Docling not up (`stage.sh ingest`), or `docling-path` wrong for the image version |
| `unrecognized configuration parameter "hnsw.iterative_scan"` | pgvector older than 0.8 |
| `expected 1024 dimensions` | Embedding model changed without re-creating the table and re-ingesting |
| Requests hang, then time out | Ollama out of memory or swapping — `docker stats` |
| Model list empty in Open WebUI | Its stored connection key is stale; edit it in Admin Panel → Settings → Connections |
| Traces vanish when navigating in Phoenix | Stale time window in the UI; click the project or set a wide range |
| Script fails with "not found" inside a container | CRLF line endings — see `.gitattributes` |
