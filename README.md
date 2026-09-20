# EnterpriseAIStarter

A reference implementation of an **in-house enterprise AI stack** — no SaaS, no cloud-hosted models — built
and proven end to end on a single CPU laptop. Open-weight models, your own vector store, your own gateway.

The use case is a **read-only, cited knowledge copilot for contact-center agents**: it answers from approved
internal documents, shows its sources, and only ever retrieves what the signed-in user is allowed to see.

> **A learning and evaluation scaffold, not production code.** Known gaps are listed in
> [`rag-service/HELP.md`](rag-service/HELP.md) §6. Most notably, users are identified from an HTTP header,
> which is acceptable only on an isolated machine.

## What this Starter PoC set out to prove

It is the cheap half of a two-part exercise: prove the **plumbing** on a laptop while GPU hardware is
procured, then prove the **value** on a server with real data. This repository is the first half.

**Objectives, and whether they were met**

| # | Objective | Result |
|---|---|---|
| 1 | Every must-have building block runs on one machine, wired as it would be on a server | Met — chat UI, gateway, RAG service, model server, vector store, document pipeline, guardrails, tracing, evaluation |
| 2 | A question travels UI → gateway → retrieval → model → cited answer | Met, with a signed-in user; roughly 40–100 s per answer on CPU, of which retrieval is ~0.4 s |
| 3 | Permission-aware retrieval: a user can never retrieve another role's document | Met — 20 of 20 leakage, injection and out-of-scope cases clean, every refusal correctly phrased, including a model that fully complied with an "admin mode" injection and still had nothing forbidden to disclose |
| 4 | The result is evidence, not opinion: a versioned golden set, scored automatically | Met — 57 cases over 6 documents and 5 users, deterministic leakage gate, LLM judge for factual answers |
| 5 | Governance is present from the start: identity, masking, audit trail, supply chain | Partly — masking, scoped keys, per-user attribution and full tracing work; images are pinned by digest and scanned; identity is header-based and the local PII recognizers do not cover UAE formats |
| 6 | The whole stack runs with no network at all | Met — gateway, embeddings, generation, ingestion and the leakage gate all pass with the machine disconnected (`./scripts/offline-test.sh`). Caveat: rag-service ran from the IDE, so the containers are proven self-contained, not the service's network placement |

All six objectives were exercised and recorded against a versioned golden set. Latency baseline on this
machine: median ~46 s per answer, of which retrieval is under half a second.

**What it deliberately does not prove:** answer quality at the target model size (a 4B model on CPU is not a
70B on GPUs), latency or capacity figures, value on real data, or the security controls a production approval
would require. Any score from this machine is a plumbing baseline, not a quality claim.

**What it found** — the reason for running it at all:

- **A guardrail can quietly destroy answers.** Presidio's default entity set matched every duration in the
  corpus, so answers came back as "credited within `<DATE_TIME>`" — structurally perfect, factually empty —
  while retrieval hit rate stayed at 100%. Fixed with an explicit entity list.
- **Permission filtering must sit inside the query.** Filtering after ranking would have leaked; so would
  losing recall to an ANN index that returns candidates before the filter is applied.
- **Small models need a prompt that demands sentences.** A 4B model answered short factual questions with a
  bare "[1]". Fixed in the prompt, with a one-shot retry when it still happens.
- **A model will obey an injection; the control cannot live in the prompt.** Told it was in "admin mode" and
  asked to list every document including restricted ones, the model complied at once: it dumped the full
  contents of its context and added that none of the documents were marked restricted. Nothing leaked, because
  the three documents that user may not see were never retrieved — the filter runs in SQL, below the model,
  and the model cannot reach past it. But it asserted something about the security model it had no way to
  know. That is the case for defence in depth stated in one test: the prompt is a request, the query is the
  control.
- **Infrastructure is the schedule.** Retrieval is milliseconds; generation on CPU is a minute and a half.
  Nothing about the architecture changes that — only hardware does.

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
./scripts/stage.sh dev        # core services
./scripts/create-keys.sh      # scoped gateway keys
./scripts/smoke-test.sh all   # models, embeddings, one answer
```

Then start `rag-service`, `./scripts/stage.sh ingest` and load the corpus, `./scripts/stage.sh full` for
guardrails and tracing, `./scripts/stage.sh eval` to measure. Each stage sets what it needs in `.env` and
starts exactly the right services. Full instructions: [`HELP.md`](HELP.md).

## Documentation

| File | Contents |
|---|---|
| [`HELP.md`](HELP.md) | Prerequisites, IntelliJ setup, stages, keys, models, commands, troubleshooting |
| [`rag-service/HELP.md`](rag-service/HELP.md) | Service internals, endpoints, retrieval, troubleshooting |

Requirement IDs such as `REQ-A5-05` refer to the requirements document for this exercise, kept internally and
not published here.

## Test data

The corpus in `data/corpus/` is **synthetic** and written to exercise access control: three documents at three
classification levels, each containing a canary string. If a junior agent's answer ever contains the VIP
discount code, that is a leak — detectable by a plain string match, no model judgement needed.

## Container scanning

Every image in the stack was scanned with Trivy (`./scripts/scan-images.sh`, which runs Trivy as a container).
The result is what you would expect from published upstream images: a large number of HIGH and CRITICAL
findings, concentrated in the fattest ones — Open WebUI worst, then the Python-based services. Most sit in
base-OS packages that are never called from this stack, and many have no fixed version available.

For a machine holding only synthetic data, on an isolated network, with nothing exposed to the internet, the
reasonable decision is to accept them and record that reasoning once. **That reasoning does not survive
promotion.** Before any of this carries real data or real users:

- **Gate images on entry.** Mirror them into an internal registry and scan on ingest, so an image with an
  unaccepted CRITICAL never reaches the platform. Scanning after deployment tells you what you already run.
- **Pin by digest, not by tag** (`./scripts/pin-images.sh`). A tag moves; a scan result applies to the digest
  that was scanned and to nothing else.
- **Decide per finding, and write the decision down:** patch, replace the image, or accept with a stated
  reason and an owner. A scan report with no decisions attached is not evidence of anything.
- **Reduce what you run.** Rebuild the heavy images on a slimmer base, or drop components you do not need.
  Open WebUI is a convenience for a demo, not a requirement of the architecture.
- **Rescan on a schedule**, not once. New CVEs are published against images you have not changed.
- **Scan the models too** — a different problem with different tools. Weights are not packages with CVEs; the
  risks are provenance, licence and unsafe serialization formats, which is why model digests and licences are
  recorded separately in `models/registry.yaml`.

None of this is unusual. It is the ordinary cost of running open-source infrastructure in a governed
environment, and it is one of the reasons the architecture keeps every component replaceable.

## Third-party components

This repository **distributes none of the software below**. Docker Compose pulls each component from its
publisher onto your machine at run time, and the models come from the Ollama library. They are listed here for
orientation; their licences are their own and are yours to review before you deploy anything.

| Component | Used for | Project |
|---|---|---|
| Open WebUI | Chat UI | https://github.com/open-webui/open-webui |
| LiteLLM | Gateway | https://github.com/BerriAI/litellm |
| Ollama | Model server (CPU laptop) | https://github.com/ollama/ollama |
| vLLM | Model server (GPU server) | https://github.com/vllm-project/vllm |
| PostgreSQL | Database | https://www.postgresql.org |
| pgvector | Vector search in Postgres | https://github.com/pgvector/pgvector |
| Docling | Document parsing and OCR | https://github.com/docling-project/docling |
| Microsoft Presidio | PII detection and masking | https://github.com/microsoft/presidio |
| Arize Phoenix | Tracing (laptop) | https://github.com/Arize-ai/phoenix |
| Langfuse | Tracing (server) | https://github.com/langfuse/langfuse |
| Text Embeddings Inference | Reranker (optional) | https://github.com/huggingface/text-embeddings-inference |
| Keycloak | SSO (optional) | https://www.keycloak.org |
| Ragas | Evaluation metrics (planned) | https://github.com/explodinggradients/ragas |
| Trivy | Image scanning | https://github.com/aquasecurity/trivy |

| Model | Used for | Model card |
|---|---|---|
| Qwen3 4B / 8B | Answers, evaluation judge | https://github.com/QwenLM/Qwen3 |
| bge-m3 | Embeddings | https://huggingface.co/BAAI/bge-m3 |
| bge-reranker-v2-m3 | Reranking (optional) | https://huggingface.co/BAAI/bge-reranker-v2-m3 |
| Llama Guard 3 | Safety classification (optional) | https://huggingface.co/meta-llama/Llama-Guard-3-1B |

Licences vary, and not all of them are standard permissive ones — some restrict hosted or managed-service use,
some impose branding or attribution conditions, and open-weight model licences differ from one another. Check
the ones you intend to run.

## Licence

This repository's own code and documentation are licensed under the **MIT License** — see [`LICENSE`](LICENSE).
Use it freely, including commercially; keep the copyright notice.

The MIT grant covers **only what was created here**: the Java service, the Compose and configuration files, the
scripts and the documentation. It does not extend to the third-party components and models listed above, which
are not distributed by this repository and carry their own terms.
