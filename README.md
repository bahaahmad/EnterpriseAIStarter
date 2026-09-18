# EnterpriseAIStarter

A reference implementation of an **in-house enterprise AI stack** — no SaaS, no cloud-hosted models — and the
two-phase proof of concept used to test it. Everything runs on your own machine or your own servers:
open-weight models, your own vector store, your own gateway.

The first use case is a **read-only, cited knowledge copilot for contact-center agents**: it answers from
approved internal documents, shows its sources, and — the part that matters — only ever retrieves what the
signed-in user is allowed to see.

> **This is a learning and evaluation scaffold, not production code.** Known gaps are listed in
> [`TODO.md`](TODO.md) and [`rag-service/HELP.md`](rag-service/HELP.md) §6. Most notably, Phase 0 identifies
> users from an HTTP header, which is acceptable only on an isolated laptop.

## What is in here

| Layer | Component | Role |
|---|---|---|
| Chat UI | Open WebUI | Talks only to the gateway |
| Gateway | LiteLLM | Keys, quotas, logical model names, PII guardrail, per-user spend |
| RAG service | **This repo's Java code** (Spring Boot 3, Java 21) | Retrieve, augment, generate — and enforce access |
| Inference | Ollama (laptop, CPU) → vLLM (server, GPU) | Runs the models |
| Models | Qwen3 4B/8B, bge-m3 | Answers, evaluation judge, embeddings |
| Vector store | PostgreSQL + pgvector | Chunks with embeddings **and per-chunk ACLs** |
| Documents | Docling | PDF, DOCX, scans → markdown |
| Guardrails | Microsoft Presidio | PII detection and masking at the gateway |
| Tracing | Arize Phoenix (laptop) → Langfuse (server) | Every prompt, chunk and response |
| Evaluation | Python harness | Golden-set scoring with a hard leakage gate |

Two design rules run through all of it:

1. **Every model call goes through the gateway.** Swapping Ollama for vLLM is a config change.
2. **The model never decides what a user may see.** Permission filtering happens in SQL, inside both halves of
   the hybrid search, before the model is involved.

## Quick start

Requirements: Docker, JDK 21, Python 3.11, ~10 GB RAM for containers. Windows/WSL2, macOS or Linux.

```bash
cp .env.example .env          # simple laptop passwords, change before any real use
./scripts/pull-models.sh      # images + open-weight models
docker compose up -d          # stage 1: postgres, ollama, litellm, open-webui
./scripts/create-keys.sh      # scoped gateway keys
./scripts/smoke-test.sh all   # models, embeddings, one answer
```

Then start `rag-service` (IntelliJ run config `2 rag-service`, or `--profile app`), bring up Docling
(`--profile ingest`), and ingest the synthetic corpus. Full instructions: [`SETUP.md`](SETUP.md).

## Documentation

| File | Contents |
|---|---|
| [`SETUP.md`](SETUP.md) | Machine prerequisites, IntelliJ setup, staged bring-up, offline test |
| [`HELP.md`](HELP.md) | Components, models in Ollama, keys, running order, everyday commands |
| [`rag-service/HELP.md`](rag-service/HELP.md) | Service internals, endpoints, retrieval, troubleshooting |
| [`docs/phase0-flows.md`](docs/phase0-flows.md) | Ingest and search diagrams, test data, golden set |
| [`requirements.md`](requirements.md) | Phase 0 requirements, exit criteria, risks, deviations |
| [`TODO.md`](TODO.md) | Open items |

## Test data

The corpus in `data/corpus/` is **synthetic** and written to exercise access control: three documents at three
classification levels, each containing a canary string. If a junior agent's answer ever contains the VIP
discount code, that is a leak — detectable by a plain string match, no model judgement needed.

## Licence

This project's own code and documentation are licensed under the **Apache License 2.0** — see
[`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

It **bundles nothing**: every third-party component is pulled at runtime as a container image or a model, each
under its own licence. Several are **not** plain permissive licences — Arize Phoenix is Elastic License 2.0,
Open WebUI adds a branding-protection clause, and open-weight model licences differ from one another. If you
adopt this in an enterprise, read [`THIRD-PARTY.md`](THIRD-PARTY.md) and have legal review it. Licence review
belongs inside model approval, not after it.
