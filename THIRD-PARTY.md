# Third-party components and licences

This repository **bundles no third-party code**. Every component below is pulled at run time as a container
image or a model, directly from its publisher, under its own licence. This file is the inventory required by
REQ-D9-02 (model licence review inside model approval) and is the starting point for legal review — not a
substitute for it.

**Verification status**: `confirmed` = checked against the project's own licence page or repository on the
date shown; `to verify` = stated from documentation and not yet checked by you. Verify everything you rely on,
and record the version you verified, because licences change between releases (Open WebUI and several model
families have changed theirs).

Last checked: **September 2026** — recheck before publishing.

## Software

| Component | Used as | Licence | Status | Notes |
|---|---|---|---|---|
| LiteLLM | Gateway container | MIT | to verify | The `enterprise/` directory in the repository is under a separate commercial licence; the proxy features used here are the open ones. |
| Open WebUI | Chat UI container | BSD-3-Clause **plus a branding-protection clause** (v0.6.6+, April 2025) | confirmed | Code up to v0.6.5 remains plain BSD-3-Clause. Removing or altering the "Open WebUI" branding is a licence breach unless the deployment has ≤50 end users in any rolling 30 days, or you hold an enterprise licence or written permission. Relevant if you ever white-label the UI. |
| Ollama | Model server container | MIT | to verify | Bundles llama.cpp (MIT). |
| vLLM | Model server (server PoC) | Apache-2.0 | to verify | Not used on the laptop. |
| PostgreSQL | Database container | PostgreSQL Licence (permissive, BSD-style) | to verify | Image `pgvector/pgvector` also carries pgvector. |
| pgvector | Postgres extension | PostgreSQL Licence | to verify | |
| Docling | Document pipeline container | MIT | to verify | Downloads its own models; check those licences too. |
| Tesseract OCR | Inside Docling | Apache-2.0 | to verify | |
| Microsoft Presidio | Guardrail containers | MIT | to verify | Uses spaCy (MIT) and its language models (check per model). |
| Arize Phoenix | Tracing container | **Elastic License 2.0 (ELv2)** | confirmed | Not an OSI-approved open-source licence. Self-hosting for your own use is permitted; providing Phoenix to third parties as a hosted or managed service is not. Fine for internal PoC use; flag it if your product story involves reselling. |
| Langfuse | Tracing (server PoC) | MIT core, with some enterprise features under a commercial licence | to verify | |
| Text Embeddings Inference (TEI) | Reranker container (optional) | HFOIL / Apache-2.0 depending on version | to verify | Hugging Face changed TEI's licence terms historically — check the exact tag you pull. |
| Keycloak | SSO (optional) | Apache-2.0 | to verify | |
| Spring Boot | Java dependency | Apache-2.0 | to verify | Pulled by Maven, not bundled here. |
| Ragas / DeepEval | Evaluation (planned) | Apache-2.0 | to verify | |
| Trivy | Image scanning | Apache-2.0 | to verify | |

## Open-weight models

Model licences differ more than software licences do, and several impose use restrictions or attribution
requirements that matter in a commercial deployment. Record the exact version you approve.

| Model | Used for | Licence | Status | Notes |
|---|---|---|---|---|
| Qwen3 4B / 8B (Alibaba) | Answers, evaluation judge | Apache-2.0 for the Qwen3 releases | to verify | Earlier Qwen generations used a bespoke licence. Check the model card of the exact tag. |
| bge-m3 (BAAI) | Embeddings | MIT | to verify | |
| bge-reranker-v2-m3 (BAAI) | Reranking (optional) | Apache-2.0 | to verify | |
| Llama Guard 3 (Meta, optional) | Safety classification | Llama 3 Community Licence | to verify | **Not** a standard open-source licence: acceptable-use policy, attribution ("Built with Llama"), and a user-count threshold. Read before enterprise use. |
| Docling / spaCy internal models | Parsing, PII detection | Various | to verify | Listed on the respective project pages. |

## How to keep this honest

1. Record the **digest**, not just the tag, for each image (`scripts/pin-images.sh`) and model
   (`scripts/record-model-hashes.sh` → `models/registry.yaml`).
2. Put licence approval **inside** the model approval flow (blueprint D9 and I11), so a new model cannot reach
   production without it.
3. Recheck on every version bump. A licence verified against v1 says nothing about v2.
