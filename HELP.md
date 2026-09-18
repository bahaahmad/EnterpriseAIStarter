# EnterpriseAIStarter — project help

Working notes for the Phase 0 PoC: what each component is, how models and keys work, and the order to run things.
Per-service detail: `rag-service/HELP.md`. Flows and test data: `docs/phase0-flows.md`.
What must be true at the end: `requirements.md`. How to install: `SETUP.md`.

## 1. Components and their roles

| Component | Layer | Role here | On the server |
|---|---|---|---|
| **Open WebUI** | A1 | Chat UI; talks only to the gateway | Same |
| **LiteLLM** | A2 | Gateway: keys, quotas, logical model names, guardrail hook, per-user spend | Same, 2+ replicas |
| **rag-service** (Java) | A5 | The RAG pattern: retrieve, augment, generate; owns identity and ACL filtering | Same |
| **Ollama** | A4 | Model server (inference) on CPU | Replaced by **vLLM** on GPUs |
| **Qwen3 4B / 8B** | A4 | Generative models: answers, and the eval judge | 70B-class at FP8 |
| **bge-m3** | A4 | Embedding model: text → 1,024 numbers | Same |
| **PostgreSQL + pgvector** | D5 | `kb_chunk`: text, vector, ACLs; also LiteLLM's key and spend DB | Same, separated |
| **Docling** | D4 | File → markdown, including OCR | Same |
| **Presidio** (2 containers) | A7 | PII detection and masking, called by the gateway | Same, plus local recognizers |
| **Phoenix** | A9 / D11 | Traces: prompts, retrieved chunks, responses | Replaced by **Langfuse** |
| **Eval harness** (Python) | A9 | Golden-set scoring and the leakage gate | Same, plus Ragas |

Two rules the design rests on: every model call goes through the gateway, and the model never decides what a
user may see — the ACL filter runs in SQL before the model is involved.

## 2. Models in Ollama

There is **no registration step**. A model exists once it is pulled, and is then addressable by name.

1. `ollama pull qwen3:4b` fetches a **manifest** from the model library and the **blobs** it lists: weights in
   GGUF format, chat template, parameters, licence. Stored in the `ai-poc_ollama` volume under
   `/root/.ollama/models`, blobs named by sha256 digest. We pull through `scripts/pull-models.sh`.
2. **Names** are `family:tag`, where the tag is usually size and quantization: `qwen3:4b`, `bge-m3:latest`.
   `ollama list` shows what you have; `ollama show qwen3:4b` shows parameters, context length and template.
3. **Behaviour ships with the model.** The manifest carries the chat template, which is why OpenAI-style
   messages are formatted correctly for Qwen3 without us doing anything.
4. **Loading is on demand.** Weights enter RAM on the first request, stay for `OLLAMA_KEEP_ALIVE` (10m), then
   unload. `OLLAMA_MAX_LOADED_MODELS=2` keeps `qwen3:4b` and `bge-m3` resident together. The first request
   after a restart is slow because of the load — normal, not a fault.
5. **LiteLLM maps logical to physical.** `chat-default` → `ollama_chat/qwen3:4b` in
   `config/litellm/*.yaml`. That mapping is the only place a physical model name appears, which is what makes
   the later swap to vLLM a config change.
6. **D9 model repository is separate.** Ollama's store is a cache, not an approval record.
   `scripts/record-model-hashes.sh` writes `models/registry.yaml` with name, digest, size, licence and approver.

**Where it fails**

| Failure | Guard |
|---|---|
| A tag is not immutable — `qwen3:4b` can point to a rebuilt blob later | Record digests (D9) and compare, or eval scores are not comparable |
| Pulling is the one step that needs the internet | Mirror or file transfer in the air-gapped zone (I11) |
| Embedding model called as a chat model, or the reverse | Keep them separate in the gateway config, as we do |
| Custom variants (context length, system prompt) drift | `Modelfile` + `ollama create` makes a new local name; record it in D9 |

## 3. Keys and passwords

All in `.env`. Each key identifies one caller to one receiver, so each hop is authorized and audited separately.

| Value | Presented by | To | Purpose |
|---|---|---|---|
| `sk-litellm-master` | You, scripts, eval | LiteLLM | Admin: create keys, see spend, any model |
| `sk-openwebui-key-01` | Open WebUI | LiteLLM | Scoped: `kb-copilot`, `chat-default` |
| `sk-rag-gateway-key` | rag-service | LiteLLM | Scoped: `chat-default`, `embed-default` (stops a loop through itself) |
| `sk-rag-service-key` | LiteLLM | rag-service | Proves the call came through the gateway |
| `postgres_pw` | LiteLLM, rag-service, you | Postgres | DB login |
| `rag_reader_pw` | rag-service (server) | Postgres | Read-only role for the query path |
| `keycloak_pw` / `webui_secret` | — | Keycloak / Open WebUI | Admin login; session cookie signing |

Keys identify **applications**. The **person** is identified by the email header, which is not a credential —
acceptable on a laptop only; a Keycloak token replaces it on the server. On the server all of these move to
Vault (I10) and are rotated.

## 4. Running order

1. Extract the latest sparse zip; delete anything listed as removed; Maven → Reload Project.
2. Reset containers if a password or the DB schema changed:
   `docker compose down` → `docker volume rm ai-poc_pgdata` → `docker compose up -d`.
   Never `down -v` unless you want to re-download the models.
3. `./scripts/create-keys.sh` (after Postgres and LiteLLM are healthy).
4. Smoke-test the gateway: run config **smoke-test-04 all** (or `./scripts/smoke-test.sh all`) —
   checks the model list, embedding dimensions and one plain answer, with timings.
5. Start **2 rag-service**; check `/actuator/health`.
6. Bring up Docling: **1b Stack: + ingest**.
7. Ingest: **3 Ingest corpus** → expect ~`{"POL-REF-001":3,"POL-VIP-002":2,"SOP-FRD-003":2}`; verify in SQL.
8. Search, direct to rag-service: `requests.http` junior → leakage probe → senior.
9. Search through the gateway: same questions. Empty answers here but not in step 8 = identity header not
   forwarded (risk R1).
10. Open WebUI at `localhost:3000`: sign up, create the three test users, select `kb-copilot`.
11. `docker compose stop docling` to free ~3 GB.
12. Eval once answers look sane: **4 Eval: golden set**.

## 5. Everyday commands

```bash
docker compose ps                      # health
docker stats                           # memory — first place to look when things "hang"
docker compose logs -f litellm         # gateway decisions, guardrail errors
docker compose logs -f ollama          # model load/unload
docker compose exec ollama ollama list # models present
./scripts/smoke-test.sh all            # gateway: models, embeddings, one answer
docker compose stop docling            # free memory after ingest
docker compose down                    # stop, keep data
```

```sql
-- what is in the knowledge base
SELECT doc_id, chunk_no, allowed_groups, left(content, 60) FROM kb_chunk ORDER BY doc_id, chunk_no;
```

## 6. Open items

Tracked in `TODO.md` — image pinning, D9 licences, Trivy, golden-set growth, open decisions.

## 7. Notes to capture later

- MCP (optional stage 5): host = rag-service, client = MCP Java SDK, server = a read-only `search_kb`;
  LiteLLM can act as MCP gateway. Not in Phase 0 — the model would decide whether to search.
- Masking documents at ingest, not just questions at the gateway.
- Reranker (TEI) on/off decision (OD3), Ragas version to pin (OD4).
