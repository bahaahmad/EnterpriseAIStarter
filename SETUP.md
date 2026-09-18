# Laptop and IntelliJ setup — Phase 0 (Windows, CPU)

Repo layout, what each part is, and the order to bring it up. Target: the full must-have stack running
end to end, then provable with the network off.

```
EnterpriseAIStarter/        (Docker Compose project name stays ai-poc)
├─ docker-compose.yml        all infrastructure containers (A1 A2 A4 A7 A9 D4 D5)
├─ .env                      single source of all settings and passwords (.env.example = reference copy)
├─ config/litellm/           A2 gateway: stage1 config (plain) and full config (Presidio + tracing)
├─ config/identity/          Phase 0 stand-in for Keycloak groups
├─ db/init.sql               D5 schema: chunks, ACL array, HNSW + full-text indexes
├─ rag-service/              A5 — Java 21 / Spring Boot, OpenAI-compatible API ("kb-copilot")
├─ eval/                     A9 — Python harness, golden-set scoring, leakage gate
├─ data/corpus/              D1/D10 — synthetic docs + manifest with ACLs
├─ data/golden/              D8 golden set incl. leakage cases
├─ models/registry.yaml      D9 — generated: model digests, licence, approval
├─ scripts/                  pull, hash, scan, key creation
├─ .run/                     shared IntelliJ run configurations (numbered in run order)
├─ requests.http             IntelliJ HTTP client smoke tests
└─ http-client.env.json      variables for requests.http (environment "local")
```

## 1. Machine prerequisites — Windows, 16 GB RAM, 8 cores, CPU only

| Item | Setting |
|---|---|
| Docker | Docker Desktop, WSL2 backend |
| WSL2 memory | `%UserProfile%\.wslconfig` → see below, then `wsl --shutdown` |
| Models | Ollama **in Docker** (CPU), `qwen3:4b` answerer, `qwen3:8b` judge, `bge-m3` embeddings |
| JDK | Temurin 21 |
| Python | 3.11+ for Windows (`py -3.11`) |
| Shell | Git for Windows (Git Bash) — run configs and scripts use it |
| Other | Trivy (`winget install AquaSecurity.Trivy`) |

```ini
# %UserProfile%\.wslconfig
[wsl2]
memory=10GB
processors=7
swap=8GB
```

Memory budget with everything up: containers ≈ 10.5 GB (limits in `docker-compose.yml`), Windows + IntelliJ +
rag-service ≈ 5–6 GB. That is over 16 GB, which is why components come up by stage and Docling and Open WebUI
are stopped during eval runs. Expect swap; slow is accepted, OOM-killed is not — check
`docker stats` when something "hangs".

**Before cloning:** `git config --global core.autocrlf input`. `.gitattributes` forces LF on scripts, SQL and YAML;
a CRLF shell script fails inside a container with a confusing "not found" error.

## 2. IntelliJ

1. **Open** the `EnterpriseAIStarter` folder. Trust the project.
2. **JDK**: Project Structure → SDK → Temurin 21, language level 21.
3. **Maven**: right-click `rag-service/pom.xml` → *Add as Maven Project*. Set any internal mirror in
   `%UserProfile%\.m2\settings.xml` (I11 rehearsal).
4. **Plugins**: Docker (bundled), Python, .env files support. Ultimate adds Spring, Database Tools, HTTP Client.
5. **Terminal**: Settings → Tools → Terminal → Shell path `C:\Program Files\Git\bin\bash.exe`.
6. **Python**: `cd eval && py -3.11 -m venv .venv && .venv/Scripts/pip install -e .`, then add `eval` as a Python
   module with that interpreter; mark `eval/src` as Sources Root.
7. **Docker**: Settings → Build, Execution, Deployment → Docker → Docker for Windows. Services tool window
   (Alt+8) shows containers, logs, `docker stats`.
8. **Database**: PostgreSQL → `localhost:5432`, db `kb`, user `postgres`, password `postgres_pw`.
9. **Run configurations** (from `.run/`; if Git is installed elsewhere, fix the interpreter path in each):

| Config | Stage |
|---|---|
| `1 Stack: core (stage 1)` | postgres, ollama, litellm, open-webui |
| `1b Stack: + ingest (stage 2)` | + docling |
| `1c Stack: full (stage 3-4)` | + presidio, phoenix, full gateway config |
| `2 rag-service` | Spring Boot, `-Xmx512m`, debuggable; settings from `.env` |
| `3 Ingest corpus` / `Docling: stop` | run ingestion, then free 3 GB |
| `4 Eval: golden set` | scoring and leakage gate |

## 3. First bring-up (connected, Git Bash)

```bash
./scripts/pull-models.sh           # all images + models, D9 registry
docker compose up -d               # stage 1
./scripts/create-keys.sh           # creates the scoped keys with the fixed values in .env
```

### Settings and passwords
Everything is in `.env`: Docker Compose reads it automatically, rag-service imports it through
`spring.config.import` (no values in the run configuration), and the eval harness and scripts load it too.
Change a value in one place and restart what uses it. Passwords are deliberately simple:

| What | Value |
|---|---|
| Postgres (`postgres`) | `postgres_pw` |
| Postgres read-only (`rag_reader`) | `rag_reader_pw` |
| Keycloak admin | `keycloak_pw` |
| LiteLLM master key | `sk-litellm-master` |
| Scoped keys: rag-service / Open WebUI | `sk-rag-gateway-key` / `sk-openwebui-key-01` |
| Key LiteLLM uses to call rag-service | `sk-rag-service-key` |

`.env` must keep plain `KEY=value` lines — no inline comments or quotes — because Spring reads it as a
properties file. Changing a database password after the first start needs `docker compose down -v`
(the init script only runs on an empty volume), followed by `create-keys.sh`.

## 4. Stages — same Phase 0 objectives, components added as needed

| Stage | Bring up | Prove | Done when |
|---|---|---|---|
| 1 Plumbing | core + rag-service | UI → gateway → rag-service → gateway → model; identity header arrives (R1) | `kb-copilot` answers the no-answer sentence for an empty KB; rag-service log shows the user email |
| 2 Ingestion + retrieval | + docling (stop after ingest) | ACL-filtered hybrid search, role matrix by hand | `requests.http` role probes pass |
| 3 Guardrails + tracing | `LITELLM_CONFIG=config.full.yaml`, + presidio, phoenix | PII masked pre-call; full trace per request | Phoenix shows every hop |
| 4 Evidence | stage 3, Open WebUI and Docling stopped | Golden set, baselines, leakage 0, offline run, pinning, Trivy | Exit criteria X1–X8 |

For the `chat-quality` (8B) judge run, raise the Ollama `mem_limit` to `7g` and keep only postgres, ollama,
litellm and rag-service up.

URLs: Open WebUI `:3000` · LiteLLM `:4000/ui` · Phoenix `:6006` · rag-service `:8081/actuator/health`.

### Offline proof
Disconnect the network, `docker compose down`, bring stage 3 up again, restart rag-service, run eval.
Anything that fails was silently calling the internet — fix it here, not in the air-gapped zone.

## 5. Request path (what you are debugging)

```
Open WebUI ──(X-OpenWebUI-User-Email)──► LiteLLM [Presidio pre-call, key, quota, log]
   ──► rag-service /v1/chat/completions  (model "kb-copilot")
         ├─ users.yaml → groups          (fail closed if unknown)
         ├─ LiteLLM /embeddings (bge-m3)
         ├─ pgvector hybrid search, ACL filter inside the query
         └─ LiteLLM /chat/completions (chat-default = qwen3:4b) with numbered sources
   ◄── answer + sources ──► traces to Phoenix
eval ──► rag-service direct (per test user) + LiteLLM judge (chat-quality)
```
