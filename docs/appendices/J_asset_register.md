# Appendix J. Asset and Licence Register

Per Track 3 ToR section 3. Enumerates every third-party code library, model weight, dataset, API, prompt template, and design asset used by InsureFlow.

Generation. The SBOM component list is auto-produced by `syft` per CI run against the built container images. Model, dataset, prompt-template, and design-asset rows are curated manually and reviewed per release. This document lives at `/docs/asset_register.md` and updates on every dependency change or model release.

## Code libraries

### Java (Spring Boot 3.3, Java 21)

- Spring Framework and Spring Boot. Apache 2.0
- Spring WebFlux and Reactor. Apache 2.0
- Spring Data R2DBC. Apache 2.0
- Lombok. MIT
- Drools 9. Apache 2.0
- Testcontainers. MIT
- JUnit 5. EPL 2.0
- Flyway. Apache 2.0
- Full SBOM at `/build/sbom/java-<service>-<sha>.json`

### Go (1.23)

- Fiber v2. MIT
- Sarama (Kafka). MIT
- pgx (PostgreSQL). MIT
- go-redis. BSD-2-Clause
- testify. MIT
- Full SBOM at `/build/sbom/go-<service>-<sha>.json`

### Elixir (1.17 with Phoenix 1.7)

- Phoenix. MIT
- Ecto. Apache 2.0
- Broadway (Kafka consumer). Apache 2.0
- Full SBOM at `/build/sbom/elixir-<sha>.json`

### Python (3.12, AI service on FastAPI)

- FastAPI. MIT
- pydantic. MIT
- Transformers (Hugging Face). Apache 2.0
- PyTorch. BSD-3-Clause
- XGBoost. Apache 2.0
- LightGBM. MIT
- scikit-learn. BSD-3-Clause
- SHAP. MIT
- PyG (graph learning). MIT
- Full SBOM at `/build/sbom/python-<sha>.json`

### Angular (19, TypeScript, Node 20 LTS)

- Angular. MIT
- RxJS. Apache 2.0
- Full SBOM at `/build/sbom/angular-<sha>.json`

### Flutter (3.24)

- Flutter SDK. BSD-3-Clause
- Dart SDK. BSD-3-Clause
- Full SBOM at `/build/sbom/flutter-<sha>.json`

## Model weights (candidate at submission, finalised per capability at bootcamp)

| Model | Purpose | Tier | Licence | Notes |
|---|---|---|---|---|
| Llama-3-8B | ICD-10 mapping (AI-5), pre-auth (AI-6), triage (AI-7), chatbot (AI-9) | T2 self-hosted candidate | Llama 3 Community License | Commercial use permitted under the 700M MAU threshold |
| Phi-3-mini | Alternative to Llama-3-8B for the same capabilities | T2 candidate | MIT | |
| Mistral-7B | Alternative to Llama-3-8B for the same capabilities | T2 candidate | Apache 2.0 | |
| MobileFaceNet-class face-embedding | On-device face-embedding for AI-1 | Edge | Apache 2.0 (reference implementation) | |
| LayoutLM v3 | Cloud OCR path for AI-4 | T2 candidate | CC BY-NC-SA 4.0 | Non-commercial restriction. Alternative under evaluation |
| MobileNet | Offline Flutter OCR path for AI-4 | Edge | Apache 2.0 | |
| Tesseract | Offline Flutter OCR path for AI-4 | Edge | Apache 2.0 | |
| OpenAI GPT-4o and GPT-4o-mini | Tier-1 candidate for non-PHI paths | T1 hosted API | Commercial terms per OpenAI API agreement | Per-tenant billing arrangement |
| Google Gemini 1.5 Pro and Flash | Tier-1 candidate for non-PHI paths | T1 hosted API | Commercial terms per Google Cloud agreement | Per-tenant billing arrangement |

## Datasets

| Dataset | Source | Licence | Notes |
|---|---|---|---|
| Synthetic claim, policy, and provider seed (around 50k records across 6 lines) | Team-generated (faker plus GAN augmentation) | Public. Own generation | Validated via Kolmogorov-Smirnov and chi-squared correlation tests |
| ICD-10 catalogue | WHO public release | Public domain | Direct download from `www.who.int/classifications/icd/en/` |
| AHFoZ tariff mechanism | AHFoZ public structural docs plus per-tenant live schedules | Public docs. Live schedules per tenant contract | Live schedules never leave the tenant boundary |
| Motor, property, life, funeral exemplars | Public court records plus synthesised | Public or synthetic | |
| Fraud case exemplars | Public reporting (Promise Banda, EcoSure, PSMAS) | Public reporting | Cited in section 1.1 |
| Weather and climate feeds (agri line, roadmap post-pilot) | Meteorological Services Department, satellite | Public API | Roadmap post-pilot |

## Third-party APIs

| API | Purpose | Provider | Notes |
|---|---|---|---|
| Ecocash | Payment collection | Cassava Fintech | Zim market leader in mobile money |
| OneMoney | Payment collection | NetOne | |
| InnBucks | Payment collection | Innscor | |
| Paynow | Payment aggregation | Paynow Zimbabwe | |
| Zimswitch | Card and POS | Zimswitch Technologies | |
| RTGS (bulk-EFT) | High-value transfer | RBZ | |
| Flutterwave | Regional card and mobile-money aggregation | Flutterwave | Roadmap Phase 2 SADC |
| Keycloak | Identity provider | Self-hosted (open source) | Apache 2.0 |
| IPEC and RBZ FX rates | Exchange-rate service | IPEC and RBZ public feeds | |

## Prompt templates and RAG assets

| Asset | Purpose | Source | Notes |
|---|---|---|---|
| ICD-10 mapping prompt template | AI-5 code assignment | Team-authored | Under `/services/python/ai-service/prompts/icd10.md`, versioned in git |
| Pre-auth reasoning prompt template | AI-6 clinical narrative parse | Team-authored | Under `/services/python/ai-service/prompts/preauth.md`, versioned in git |
| Adjudication triage prompt template | AI-7 decision plus reasoning trace | Team-authored | Under `/services/python/ai-service/prompts/triage.md`, versioned in git |
| Chatbot RAG index | AI-9 clause citation | Per-tenant policy corpus | Rebuilt per tenant on policy revision. Never crosses tenant boundary |
| IPEC return template | AI-10 regulator submission draft | IPEC public quarterly return template | Public regulator document |

## Design assets

| Asset | Purpose | Licence |
|---|---|---|
| Angular Material components | Web portal styling | MIT |
| Roboto and Arial fonts | Web and PDF typography | Apache 2.0 for Roboto, Monotype licence for Arial system |
| Custom logos and icons | Brand | Team-authored, all rights reserved to [Team Name] |

## Change log

Every code-library entry updates on the corresponding CI run. Manual entries (models, datasets, prompt templates, design assets) update per release. Full change history in git log against this file.
