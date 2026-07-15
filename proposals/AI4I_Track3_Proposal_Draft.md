# InsureFlow
## An AI-Native Core Insurance Operating Platform for Zimbabwean and SADC Insurers

Track: Track 3, Development
Team Name: [Team Name]
Lead Innovator: [Full Name]
Date: 14 July 2026

Submission artefacts:

- Repository: https://github.com/MecksonMfema/medfund. Access: public during the AI4I judging window (14 Jul to 1 Aug 2026), or private with judge accounts invited as read-only collaborators. State the access mode on the portal submission form.
- Hosted demo: [Demo URL]. Adjudicator smoke test passes per section 3.1 bootcamp definition of done.
- Appendices: In repo at `/docs/appendices/` (A through J, per the appendix list at the end of this document).

\newpage

## Section 1. Problem Definition and Strategic Alignment

### 1.1 The whole insurance operating stack is broken, not one workflow

Zimbabwean and SADC insurers run on decades-old core systems, spreadsheets, WhatsApp groups, paper forms, and incompatible databases stitched together by human effort. The patchwork breaks at every stage of the insurance lifecycle. Onboarding, KYC, underwriting, premium collection, provider management, pre-authorisation, adjudication, payout, engagement, analytics, and regulatory reporting all fail. Claims are one visible symptom, not the whole disease.

- Sector under-penetration in a mostly informal economy. Insurance covers 2% of Zimbabwean GDP against a Sub-Saharan average of 2.8% and a global 6% ⟨Equity Axis Apr 2026⟩. 21 short-term insurers, 12 life, 8 funeral, 9 microinsurers, and 36 AHFoZ medical-aid societies produce around 122 addressable buyers ⟨IPEC 2024, AHFoZ 2026⟩. 76.1% of businesses operate informally ⟨ZimStat 2023⟩. Current stacks miss informal buyers.
- Broken enrolment, KYC, and collection. Paper forms, no digital ID, no biometric verification, single-channel collection. Q3 2025 lost 97,111 life policies. Nhaka Life recorded a 27.59% lapse ratio ⟨The Zimbabwean Dec 2025⟩.
- Fraud runs across the whole lifecycle. Application (Promise Banda EcoSure Child-Health-Card fraud, Jul 2025), financial control (PSMAS: USD 60 m misappropriated 2018 to 2022 through a "defective claim management system" ⟨Newsday 2023⟩), and claim (upcoding, duplicate invoices). Aggregate leakage reaches 30 to 40% of paid claims. IPEC estimates USD 165 m per year lost, USD 451,000 recovered in 2023 ⟨allAfrica Sep 2025⟩.
- Adjudication delay and provider friction. 74% of 155 IPEC complaints in 2025 concern claim-settlement delays, up 23% year-on-year ⟨Zim Independent Dec 2025⟩. 8 of 21 short-term insurers held negative working capital at end-2024. A USD 2.47 m Harare court judgment came from one fire claim ⟨Herald 2025⟩. Doctors receive USD 5 for USD 25 tariffs, reimbursement around a year behind ⟨Newsday 10.05.2026⟩. Pre-auth over phone and WhatsApp with no consistency and no audit trail.
- Data, currency, and regulator blindspot. No incumbent knows a real-time loss ratio or provider-spend outliers. The ZiG dual-currency regime since April 2024 forces every premium, reserve, claim, and ledger entry to reconcile across USD (77% of short-term revenue) and ZiG ⟨IPEC 2024⟩. IPEC returns get compiled by hand.
- No modern engagement or embedded-insurance surface. No digital ID, no self-service, no in-language chat. A distressed family left a coffin at an EcoSure branch after a funeral claim was denied over a missed USD 4 premium ⟨iHarare 2025⟩. Telcos, banks, mobile-money, and e-commerce partners want to offer bundled cover. No incumbent exposes an API or partner portal.

The common thread runs across all six problems. Zimbabwe lacks a modern, multi-tenant, AI-native core insurance operating platform to carry an insurer's whole book across every line, currency, channel, and partner, from first quote to regulator return. InsureFlow fills the gap.

### 1.2 What InsureFlow is

InsureFlow is a multi-tenant core insurance operating platform delivered as SaaS. The platform runs the full lifecycle: enrolment, policy administration, contributions and premium billing, provider network management, claims intake and adjudication, finance and regulatory reporting, and member engagement. AI enters at pricing, underwriting, fraud, adjudication, churn, document intelligence, and multilingual member service. The platform ships with seven role-scoped portals, a per-tenant hot-reloadable rules engine, and an evidence-grade audit trail. The design is modular. Large insurers adopt the whole platform. Microinsurers run only claims and engagement. Brokers run only enrolment and billing. TPAs run the platform for multiple carriers. Regulators consume the fraud graph and reporting API.

### 1.3 Target users

| User group | Portal |
|---|---|
| Insurance carriers (insurers, medical aid, funeral, micro), around 122 addressable | Tenant admin |
| Third-party administrators, MGAs, brokers | Tenant admin (delegated multi-carrier) |
| Insurer operations, adjudicators, underwriters | Operations and Adjudicator |
| Healthcare providers, motor assessors, funeral directors | Provider |
| Corporate groups, employer schemes, group liaisons | Group Liaison |
| Policyholders and dependants (around 2.12 m life, 1.63 m medical-aid, 151,578 microinsurance ⟨IPEC 2025⟩) | Member (web and Flutter) |
| IPEC, ZICB | Regulator |

### 1.4 Strategic alignment

InsureFlow maps onto the Zimbabwe National AI Strategy 2026 to 2030, launched on 13 March 2026 by President E.D. Mnangagwa ⟨UNESCO, OECD.AI⟩. The platform matches Pillar 3 (AI Adoption in the priority financial-services sector), Pillar 4 (Governance and Ethics: every AI decision logs model version, features, confidence, and human review under the Cyber and Data Protection Act, Chapter 12:07), and Flagship 1 (the AI Grand Challenge, of which AI4I is the implementation vehicle). The design generalises across SADC. The Zimbabwean market runs small enough for a credible pilot. The pain (fraud, delay, dual currency, informal-sector gap, ISA-3 regulator expectations) appears in South Africa, Zambia, Malawi, Botswana, and Namibia.

Boundary statement: InsureFlow does not serve surveillance, credit denial, immigration profiling, or automated denial of care. Every high-value or contested decision keeps a human in the loop.

---

## Section 2. Technical Design and Product Logic

### 2.1 Platform overview: seven modules, seven portals, one AI service

InsureFlow runs as a family of loosely-coupled domain modules on a shared Kafka event backbone with a single AI service used by every module. Every module scopes to a tenant, tracks currency, instruments audit, and drives from rules. Users interact through six Angular web portals (super admin, tenant admin, operations and adjudicator, provider, group liaison, regulator read-only), plus a Flutter member app (Android, iOS, PWA, offline-first), a REST OpenAPI 3.1 surface for embedded-insurance partners, a multilingual chatbot (English, Shona, Ndebele), and webhook events for partner systems. Every portal scopes to a role, protects with MFA, and authenticates through OIDC.

Seven domain modules: Policy Administration (products, plans, biometric-KYC enrolment, dependants, group schemes), Contributions and Premium (billing cycles, multi-gateway collection across Ecocash, OneMoney, InnBucks, Paynow, Zimswitch, RTGS, bulk-EFT, arrears, revocation), Claims and Adjudication (intake, AI-assisted pre-auth, six-stage adjudication, payout, dispute, appeal), Provider Network (biometric-KYC onboarding, tariff catalogue, pre-auth, reconciliation), Finance and Regulatory (GL, reserves, payout orchestration, reinsurance ceding, IPEC returns, multi-currency reconciliation), Engagement (multilingual SMS, email, WhatsApp, push, in-app chat, digital ID, live activity stream), and Data, Analytics, and Insight (cross-cutting, section 2.4). Portal capability matrix in Appendix I.

### 2.2 System architecture

Angular web portals and the Flutter member app authenticate through a Go and Fiber API gateway (OIDC via a per-tenant Keycloak zone plus TOTP, Email, and SMS MFA) into Java 21 and Spring Boot 3.3 WebFlux domain services (tenancy, user, policy admin, contributions, claims, provider network, finance and regulatory), each backed by a schema in a PostgreSQL 17 cluster. Cross-cutting Go 1.23 and Fiber v2 services (audit, notification, file, payment-gateway covering Ecocash, OneMoney, InnBucks, Paynow, Zimswitch, RTGS, Flutterwave) ride an Apache Kafka event backbone alongside a Drools 9 per-tenant hot-reloadable KieBase rules engine and a Python 3.12 and FastAPI AI service running the three-tier model stack of section 2.3. An Elixir 1.17 and Phoenix 1.7 live-dashboard and chat service handles WebSocket concurrency at ops-team scale. Observability runs via OpenTelemetry to Loki, Tempo, and Mimir. Full architecture render, service boundaries, event topics, and integration map appear in Appendix A.

Flutter edge budget (rubric C4). On-device face-embedding (MobileFaceNet-class candidate) under 10 MB. Edge OCR (LayoutLM int8 or MobileNet plus Tesseract candidate) under 256 MB RAM, under 100 ms per page on mid-range Android. Offline-first claim intake queues to encrypted local SQLite and syncs to Kafka on reconnect with idempotent server-side dedup. Digital-ID QR renders offline. MFA cache respects Keycloak token TTL.

### 2.3 AI fit: where AI earns a place, and where AI does not

The AI4I rubric penalises "sledgehammer" designs. InsureFlow adopts an explicit rules-first principle. Any decision expressible as a deterministic lookup, inequality, or arithmetic operation goes to the Drools rules engine. The rules engine hot-reloads per tenant, versions cleanly, tests in isolation, and runs orders of magnitude cheaper than a model call. AI enters only where rules prove insufficient.

Model selection stays open at submission. Three tiers form the model stack.

- Tier 1. Hosted APIs (OpenAI GPT-4o or Gemini 1.5). Fastest to deploy, highest baseline quality. Trade-off: PHI crosses to a US processor (section 4.1) and per-call token cost applies. Used for non-PHI paths: regulator return drafting, non-clinical chatbot, ops summarisation.
- Tier 2. Self-hosted open-weight models (Llama-3-8B, Phi-3-mini, Mistral-7B on ZCHPC). DPA-clean, Zim-hosted, tenant-isolated. Base for PHI paths: ICD-10, clinical pre-auth, adjudication triage on health and life narratives.
- Tier 3. Custom models we train for layers where no off-the-shelf model fits or where tenant-specific behaviour must be learned: fraud GBM plus GNN plus isolation-forest ensemble (AI-3), underwriting GBM risk residual (AI-2), pre-auth appropriateness classifier and provider-behaviour score (AI-6), churn survival plus causal uplift (AI-8), Zim-ID-format duplicate-identity and liveness anti-spoof adapter (AI-1), and LoRA fine-tunes for Shona, Ndebele, and ICD-10 domain adaptation (AI-5, AI-9). Every custom model ships with a model card, a bias slice-test result set, and a version pinned in the audit trail (section 4.2).

Ten AI capabilities across the lifecycle. Full tier, model, and evidence detail in Appendix F.

- AI-1 Biometric KYC. T1 or T2 doc parser plus T3 Zim-ID adapter on MobileFaceNet-class face-embedding.
- AI-2 Underwriting and risk pricing. GLM base plus T3 GBM residual plus SHAP per quote.
- AI-3 Cross-domain fraud detection. T3 GBM plus graph model plus isolation-forest ensemble at application, mid-lifecycle, and claim. Deloitte: soft-fraud detection rises from 20 to 40% under rules to 70 to 80% under AI ⟨Deloitte 2025⟩.
- AI-4 Document intelligence. T1 vision LLM or T2 layout model in cloud, MobileNet plus Tesseract on Flutter edge.
- AI-5 Semantic ICD-10 and tariff-code mapping. T2 open LLM plus T3 LoRA on tenant corpus. Target 94% top-100 ICD-10 accuracy ⟨medRxiv Jul 2025⟩.
- AI-6 AI-assisted pre-authorisation. Rules-first eligibility plus T2 LLM narrative plus T3 appropriateness classifier plus T3 provider-behaviour score.
- AI-7 Adjudication triage. T2 for PHI, T1 otherwise. Allianz "Project Nemo": 80% claim-processing time reduction ⟨Allianz Nov 2025⟩.
- AI-8 Churn, lapse, and payment-recovery uplift. T3 Cox plus gradient-boosted survival forest plus causal uplift.
- AI-9 Multilingual member and provider assistant. T2 open LLM plus T3 LoRA on Shona and Ndebele, RAG over tenant policies.
- AI-10 Regulatory return generation. T1 acceptable (regulator-facing, not PHI). LLM plus human sign-off.

Post-pilot AI roadmap, each with a rules-gap. Complaint and sentiment triage (Engagement, free-text urgency classification). Next-best-offer (Engagement, learned uplift). Provider quality-of-care outlier (Provider Network, anomaly on clinical outcomes). Reserve setting and IBNR (Finance, actuarial tail-loss ML). Shona and Ndebele voice interface (Engagement, STT and TTS). AML transaction monitoring (Finance, sequence anomaly on payment flows).

Where AI stays out (the anti-sledgehammer argument). Tariff lookups, benefit-limit arithmetic, waiting-period checks, per-member annual caps, USD to ZiG conversion, policy expiry, PEP-list-only KYC checks, deterministic pre-auth eligibility, and any decision a lawyer or actuary writes as an inequality. All live in the per-tenant Drools rules engine.

### 2.4 Data, Analytics, and Insight: the platform's second value proposition

Every business event flows into a schema-per-tenant OLTP store and projects into a cross-module analytics store. Architectural principle: stats run server-side. No Angular or Flutter client aggregates business data. Every KPI, chart, and forecast runs as a pre-computed endpoint with correct tenant scoping, currency handling (USD and ZiG), and point-in-time snapshotting. Analytics domains served: underwriting and actuarial (loss ratio, combined ratio, IBNR, benefit utilisation, claim frequency and severity), financial and regulator (income slices, reserves, IPEC returns, per-line and per-currency GWP), provider and fraud (spend outliers, tariff drift, cross-tenant fraud graph), and member and ops (LTV, retention, lapse forecasts, adjudication TAT, backlog, payment-success by PSP). The store feeds back into the AI service. AI-2, AI-3, AI-6, and AI-8 retrain on rolling windows. Every retraining event versions to the audit trail (section 4.2). Kafka schema-versioned events give a departing tenant a standards-compliant export. DPA portability holds by design.

### 2.5 Data statement

Data sources at submission. Team-generated synthetic claim, policy, and provider seed (around 50k records across 6 lines, faker plus GAN augmentation) under `/data/synthetic/`. WHO ICD-10 catalogue (public domain) under `/data/icd10/`. AHFoZ tariff mechanism structural docs (public) with live schedules per tenant contract. Motor, property, life, and funeral exemplars from public court records plus synthesis under `/data/exemplars/`. Public fraud reporting (Promise Banda, EcoSure, PSMAS) under `/data/fraud_cases/`. Real pilot-tenant data lands post-bootcamp under DPA consent plus processor agreement. Weather and climate feeds for the agri line ship post-pilot from Meteorological Services Dept and satellite APIs.

Synthetic data validates through Kolmogorov-Smirnov and chi-squared correlation tests on age, geography, claim amount, and provider mix (`/validation/synthetic_validation_v1.pdf`). Known limitation. Synthetic data does not replicate rare collusion patterns, so fraud models re-benchmark on the first tenant's real data before production. Full source, rights, and licensing table in Appendix F.

### 2.6 Prototype credibility and honest maturity matrix

The GitHub repository is at https://github.com/MecksonMfema/medfund (Appendix B for full layout and README). At submission the repository contains around [N] merged commits across [M] services, with green CI, Testcontainers-backed integration tests, and dependency-locked manifests. No component gets claimed as production-ready when the component is not.

Full status at submission (per Appendix H). Full: tenancy, user service, RBAC, Keycloak per-tenant zones, policy administration, contributions and premium billing, three-line claims and adjudication (health, motor, funeral), rules engine with per-tenant KieBase isolation, tariff catalogue with annual cap, templated notifications, three Angular portals (Member, Adjudicator, Tenant Admin). Partial: Provider Network (onboarding and tariff live, pre-auth scaffolded), Finance and Regulatory (reports operational, GL and reserves in-flight), Flutter member app (core screens working, biometric-KYC in-flight), Provider portal. Functional and improving: audit trail (actor email, entity name, correlation ID enrichment extending). Design or stubs: payment gateway integrations (Ecocash targeted for bootcamp), live activity stream, Group Liaison and Regulator portals. Open: AI Service tier selection per capability (bootcamp and pilot decision, section 2.3), with AI-1, AI-4, AI-5, and AI-7 wired with candidate tier. See Appendix A for the single-page architecture render, Appendix C for the demo script, and Appendix H for the testing evidence pack.

---

## Section 3. Deliverables and CCE Implementation Roadmap

### 3.1 Bootcamp deliverables and 90-day post-challenge roadmap

Four delivery windows, full window-by-window table in Appendix C.

- Bootcamp, 27 Jul to 1 Aug 2026. Policy Admin, Contributions, Claims (subset), Engagement. Ecocash live. Live-notification MVP to Adjudicator. First 5 KPI endpoints. AI-1 KYC, AI-4 OCR, AI-5 ICD-10, AI-7 triage (shadow). Done: live demo across 3 lines on a seeded tenant, hosted URL passes adjudicator smoke test.
- Days 0 to 30. Plus Provider Network complete, audit-trail enrichment closed, OneMoney, live-notification fan-out to Member app. Plus AI-3 fraud v1, AI-9 chatbot (English). Done: 1 pilot tenant sandbox live, 500 anonymised claims processed end-to-end, ICD-10 F1 ≥ 0.85 on top-100, KYC false-accept ≤ 0.5% on liveness test set.
- Days 31 to 60. Finance and Regulatory GL plus reserves, Group Liaison module, Paynow, Zimswitch/POS, RTGS bulk-EFT. Underwriting book plus financial-reporting analytics. Plus AI-6 pre-auth (shadow), AI-8 churn, AI-9 Sn+Nd, AI-2 pricing v1. Done: fraud AUC ≥ 0.80 on synthetic plus first-tenant hold-out, pre-auth shadow agreement with human ≥ 80%, chatbot cites clause on ≥ 90% test cases, DPA controller licence submitted, payment-success ≥ 95% across Ecocash+OneMoney+Paynow.
- Days 61 to 90. Full stack. Live activity stream fan-out to all 7 portals. Full analytics suite. Regulator export API. Plus AI-3 GNN upgrade, AI-6 live, AI-10 IPEC return generator. Done: shadow-mode adjudication agreement ≥ 85%, claim TAT improvement ≥ 50% on shadowed claims, pre-auth turnaround < 10 min, first IPEC return auto-generated end-to-end, first regulator briefing to IPEC.

### 3.2 Compute environment (CCE) plan

The AI4I ToRs require a deployment plan for the ZCHPC Controlled Compute Environment (full plan in Appendix E). ZCHPC publishes on zchpc.ac.zw. HPC Cloud Account, Linux KVM VPS, dedicated Windows servers, colocation, and incubation support. The National AI Strategy also names a POTRAZ-supervised "Innovation Crucible" AI Regulatory Sandbox ⟨National AI Strategy 2026, OECD.AI⟩. InsureFlow targets both.

- Training: 1 A100-class GPU on the ZCHPC HPC Cloud Account for the Tier-3 custom-model layer (LoRA fine-tunes for ICD-10, triage, and Sn/Nd chatbot, plus fraud GBM/GNN, churn survival, uplift, and appropriateness classifiers). 90-day budget around 150 GPU-hours if the platform trends self-hosted, around 40 GPU-hours if hosted APIs cover the LLM tier.
- Inference: CPU Kubernetes pods on ZCHPC KVM VPS for edge OCR, fraud, churn, uplift, and rules-engine services. Steady-state per tenant at 10 k claims per day is around 8 vCPU and 24 GB RAM without self-hosted LLM, plus 1 A10-class GPU and 32 GB VRAM with Tier-2 self-hosted LLM inference.
- Regulatory sandbox: registration with the POTRAZ Innovation Crucible for pre-production bias and compliance testing (National AI Strategy Pillar 4). Fallback: Helm chart deploys to any Kubernetes 1.29+ cluster (/deploy/helm/), SHA-pinned images, SBOM per build.

### 3.3 Dependency locking and asset and licence register

All manifests version-lock. Gradle 8.7 plus libs.versions.toml (Java 21, Spring Boot 3.3.x pinned). go.mod plus go.sum (Go 1.23). mix.lock (Elixir 1.17 and OTP 27). uv.lock (Python 3.12). package-lock.json (Angular 19, Node 20 LTS). pubspec.lock (Flutter 3.24). Container images SHA-pin in values.yaml. SBOM (syft) publishes per CI run. Provenance attestation goes through cosign. The asset and licence register at /docs/asset_register.md in the repo (Appendix J) enumerates every third-party code library, model weight, dataset, API, prompt template, and design asset with the licence, source, and rights basis (per Track 3 ToR section 3).

### 3.4 Testing and validation

- Unit tests. JUnit 5, Go testing plus testify, ExUnit, pytest. Coverage target 70% or higher on business-logic packages.
- Integration tests. Testcontainers (PostgreSQL, Kafka, Redis, Keycloak, WireMock), with tenant-isolation, multi-currency, and audit-trail guard-rails committed.
- Load. k6 at 10,000 claims per hour sustained. P99 adjudication latency under 800 ms rules-only, under 2,500 ms with LLM triage.
- Model unit tests. Per-model precision, recall, and AUC on synthetic plus first-tenant hold-out. Bias slice tests on gender (where lawful), province, provider tier, and language before every release.
- Human-in-the-loop. Every claim over a tenant-configurable value threshold, or with a fraud score in the grey zone, routes to a human adjudicator with the AI reasoning trace visible.

---

## Section 4. Compliance and Risk Mitigation

### 4.1 Cyber and Data Protection Act (Chapter 12:07) compliance

The Cyber and Data Protection Act (Act No. 5 of 2021, commenced 11 March 2022 via GN 492 of 2022) names POTRAZ as the Cyber and Data Protection Authority. The Act treats health, biometric, and financial data as sensitive personal data. SI 155 of 2024 mandates data-controller licensing and DPO appointment for insurance, banking, and healthcare controllers ⟨POTRAZ, veritaszim, MISA Zimbabwe 14 Mar 2025⟩.

Controls at a glance (full obligation and control register in Appendix G):

- Lawful basis for processing. Explicit granular consent checkboxes render in the Angular enrolment wizard and Flutter member app, one per processing purpose (enrolment, adjudication, fraud analytics, marketing), versioned per policy revision, logged to the immutable audit trail with tenant-scoped retrieval by the data subject.
- Purpose limitation and data minimisation. Every field tags with `purpose[]`, queries assert purpose match, only fields required for the stated task ingested, and PII tokenised at rest before training.
- Sensitive-data controls. AES-256 at rest, TLS 1.3 in transit, per-tenant KMS keys, RBAC with break-glass audit.
- Cross-border transfer. Zim-hosted primary. If a Tier-1 hosted LLM (OpenAI, Gemini) is selected for a capability touching PHI or biometric data, the platform routes the capability to a Tier-2 self-hosted model or refuses the call (section 2.3). Every hosted-API sub-processor is listed with a lawful-basis and data-residency assessment before enablement.
- Breach notification. Automated `security-events` topic, 72-hour SLA to POTRAZ.
- Retention. Per-tenant policy in Drools, automated deletion with tamper-evident audit.
- Data-subject rights. Access, correction, portability, and objection wired through the admin portal and Flutter app.
- DPO. Named from Day 0. POTRAZ controller licence submitted within 30 days.

### 4.2 Auditability of AI decisions

Every AI-assisted decision (risk-pricing quote, fraud flag, adjudication triage, ICD-10 mapping, churn score, chatbot escalation, regulatory-return line) logs model version, input features, confidence score, output, and human-review outcome, immutably, to the ai-decisions topic. Every entity mutation logs separately to audit-events with actor, actor email, old value, new value, changed fields, and correlation ID. Both streams append only. No update or delete path exists. The design satisfies National AI Strategy Pillar 4 and gives IPEC an integration point for the emerging ZICB.

### 4.3 Cybersecurity

A per-tenant Keycloak zone runs OIDC with PKCE and mandatory MFA (TOTP, Email OTP, SMS OTP) for admin and staff. Role-based access applies within each tenant. Tenant-scoping enforces on every database query through the TenantContext interceptor. No secrets sit in the repository. Runtime resolves from a KMS-backed store. .env.example documents every variable without values. OWASP Top 10 controls apply. Input validation, parameterised queries, output encoding, CSRF, CSP. Snyk and Dependabot run on every CI. SBOM publishes per build. Login, logout, failed auth, MFA challenges, role changes, permission denials, and impersonation flow to security-events with real-time alerting on brute-force and impossible-travel patterns.

### 4.4 Responsible AI safeguards

- Human-in-the-loop on every high-value, contested, or grey-zone decision (pricing, adjudication, fraud, denial).
- Reason codes and appeal. Every quote, outcome, and denial carries a plain-language reason code in the member's language. The member app surfaces a one-tap escalation.
- Bias testing. Per-line slice tests across gender (where lawful), province, provider tier, language, and age band before every model release. Mitigation (re-sampling, thresholding, disparate-impact monitoring) publishes in the model card.
- Risk-pricing fairness. Regulator-facing GLM base rate stays explainable. ML residual caps in influence on the final premium. Every quote ships with a SHAP explanation.
- Boundary statement. No surveillance. No credit denial. No immigration profiling. No automated denial of care.

### 4.5 Key risks

Top risks, likelihoods, impacts, and mitigations. Full register in Appendix G.

- Model drift as fraud or pricing patterns evolve (High, High). Weekly rolling eval, monthly re-training, drift alerts on feature distributions.
- Cross-tenant data leak via shared rules engine (Low, Catastrophic). Per-tenant Drools KieBase plus per-tenant ReleaseId. A concurrency integration test guards the isolation.
- ZiG volatility distorting reserves and claims (High, Medium). FX-rate service on IPEC plus RBZ feeds. Every monetary field carries a currency tag. No cross-currency arithmetic.
- Slow tenant adoption (Medium, High). Microinsurance pilot first (fastest ROI). Regulator dashboard as network-effect lever.
- Biometric false-accept or false-reject (Medium, High). Passive-liveness anti-spoof, human review on rejections, per-tenant thresholds, POTRAZ-aligned biometric consent, skin-tone, age, and gender bias slice tests per release.
- Pre-auth over-approval or clinical harm (Low, High). Rules-first eligibility gate, LLM advisory below a value threshold, reasoning trace plus appeal path, provider notified within SLA regardless.
- Additional risks (AI-decision liability, risk-pricing discrimination claim, ZCHPC availability) covered in Appendix G with same mitigation grade.

---

## Section 5. Sustainability and Future Adoption

### 5.1 Business model

InsureFlow runs as a B2B multi-tenant SaaS with four revenue lines: platform subscription (USD 8 k to 40 k per tenant per month across three tiers by active policy count), Module-as-a-Service for insurers with a legacy core (USD 2 k to 6 k per module per month across fraud, engagement, chatbot, regulatory return, risk pricing, each with an integration API), fraud recovery share (15% of documented fraud prevented pre-payment, capped per tenant. At sector fraud losses of USD 165 m per year, capturing even 5% funds the platform), and a regulator API priced at regulator level for IPEC and ZICB.

### 5.2 Cost projections

Indicative monthly cost per full-platform tenant at 10 k active policies. Compute (ZCHPC HPC plus KVM VPS, custom models plus rules plus services) USD 800 to 1,800. LLM inference (Tier 1 tokens or Tier 2 self-hosted GPU) USD 400 to 2,000. Managed Postgres, backups, DR, monitoring USD 300. Shared support USD 400. Total delivery around USD 1,900 to 4,500 against tier-2 subscription around USD 15,000. Gross margin around 70 to 85%. Three-month pilot budget around USD 45,000. Twelve-month scale-up to 10 full-platform plus 5 module-only tenants around USD 380,000. Full cost model in Appendix D.

### 5.3 Pilot and scale pathway

Pilot 1 (Days 0 to 90): a microinsurance provider (highest complaint volume, simplest taxonomy, fastest TAT improvement). Pilot 2 (Months 4 to 6): a short-term insurer with acknowledged liquidity pressure per Zim Independent 2025 on the 8 insurers with negative working capital (fraud-recovery-share drives adoption). Pilot 3 (Months 7 to 12): a mid-tier medical-aid society (highest complexity, strongest AHFoZ plus ICD-10 story, defensible reference). TPA or broker onboarding follows Months 10 to 14. Zimbabwe addressable market: 150 to 200 buyers (86 IPEC plus 36 AHFoZ plus TPA, broker, and insurtech partners). SADC Phase 2 targets South Africa, Botswana, Zambia, and Namibia (Nguni chatbot already covers Ndebele and Zulu). Regulator route: IPEC first, then ZICB, then NBFIRA, FSCA, NAMFISA, PIA.

### 5.4 Team and post-challenge plan

Team: [Lead Innovator] leads platform architecture and AI service (Java, Go, Python). [TM 2] leads AI and ML (Python, LLM fine-tuning, graph learning, MLOps). [TM 3] leads frontend and UX (Angular, Flutter, accessibility). [TM 4] leads compliance and DPO (Data Protection Act). [TM 5, optional] provides sector SME depth (prior at IPEC, AHFoZ, or a carrier).

No POTRAZ, USF, or Ministry of ICT funding in the 2021 to 2026 window. Confirmed on the notarised affidavit at shortlist. Team leader stays accountable steward. Short-form services agreement signs with each pilot tenant before data flows.

Milestones. 30 days: pilot tenant onboarded, ICD-10 F1 ≥ 0.85, DPO named, POTRAZ controller licence submitted. 60 days: fraud model shadow-mode on real data, chatbot live in English, Shona, and Ndebele, DPA audit closed. 90 days: shadow-mode adjudication agreement ≥ 85%, TAT improvement ≥ 50%, first IPEC briefing.

---

## Appendices (excluded from 10-page count)

- Appendix A. Full architecture diagram (single-page render). In repo at `/docs/appendices/A_architecture.png`
- Appendix B. GitHub repository layout and README. In repo at `/docs/appendices/B_readme_pointer.md` (pointer to root `README.md`)
- Appendix C. Bootcamp demo script (screens, endpoints, expected outputs). In repo at `/docs/appendices/C_demo_script.md`
- Appendix D. Business model canvas (Product Readiness Annex A). In repo at `/docs/appendices/D_business_model_canvas.md`
- Appendix E. Deployment plan (Product Readiness Annex B). In repo at `/docs/appendices/E_deployment_plan.md`
- Appendix F. Data and AI usage note (per module, per model). In repo at `/docs/appendices/F_data_ai_usage.md`
- Appendix G. Risk and compliance checklist (Product Readiness section 15). In repo at `/docs/appendices/G_risk_compliance_checklist.md`
- Appendix H. Testing evidence pack (screenshots, logs, sample validation reports). In repo at `/docs/appendices/H_testing_evidence.md`
- Appendix I. Module and Portal matrix (which portal exposes which module capability). In repo at `/docs/appendices/I_portal_matrix.md`
- Appendix J. Asset and licence register (per Track 3 ToR section 3). In repo at `/docs/appendices/J_asset_register.md`

