# Appendix G. Risk and Compliance Checklist

Also appended to the AI4I proposal PDF after the body. This file is the repo copy for adjudicators who prefer browsing the code repository.

## G.1 Cyber and Data Protection Act obligations

| Obligation | Control |
|---|---|
| Lawful basis for processing | Explicit granular consent checkboxes render in the Angular enrolment wizard and Flutter member app, one per processing purpose (enrolment, adjudication, fraud analytics, marketing). Versioned per policy revision. Logged to the immutable audit trail with tenant-scoped retrieval by the data subject |
| Purpose limitation | Every field tags with `purpose[]`. Queries assert purpose match |
| Data minimisation | Only fields required for the stated task ingested. PII tokenised at rest before training |
| Sensitive-data controls | AES-256 at rest. TLS 1.3 in transit. Per-tenant KMS keys. RBAC with break-glass audit |
| Cross-border transfer | Zim-hosted primary. If a Tier-1 hosted LLM is selected for a capability touching PHI or biometric data, the platform routes the capability to a Tier-2 self-hosted model or refuses the call. Every hosted-API sub-processor is listed with a lawful-basis and data-residency assessment before enablement |
| Breach notification | Automated `security-events` topic. 72-hour SLA to POTRAZ |
| Retention | Per-tenant policy in Drools. Automated deletion with tamper-evident audit |
| Data-subject rights | Access, correction, portability, and objection wired through the admin portal and Flutter app |
| DPO | Named from Day 0. POTRAZ controller licence submitted within 30 days |

## G.2 Full risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Model drift as fraud or pricing patterns evolve | High | High | Weekly rolling eval, monthly re-training, drift alerts on feature distributions |
| Cross-tenant data leak via shared rules engine | Low | Catastrophic | Per-tenant Drools KieBase plus per-tenant ReleaseId. A concurrency integration test guards the isolation. Never relaxed |
| ZiG volatility distorting reserves and claims | High | Medium | FX-rate service on IPEC plus RBZ feeds. Every monetary field carries a currency tag. No cross-currency arithmetic |
| Slow tenant adoption | Medium | High | Microinsurance pilot first (fastest ROI). Regulator dashboard as network-effect lever |
| Reliance on POTRAZ or ZCHPC availability | Medium | Medium | Helm-chart parity with any Kubernetes 1.29 or newer cluster |
| AI-decision liability | Low | High | Human-in-loop on high-value decisions. Explicit AI disclosure to members. DPO oversight |
| Risk-pricing discrimination claim | Low | High | Regulator-friendly GLM base. ML residual capped and SHAP-explained. Bias slice tests |
| Biometric false-accept or false-reject | Medium | High | Passive-liveness anti-spoof. Human review on rejections. Per-tenant thresholds. POTRAZ-aligned biometric consent. Skin-tone, age, and gender bias slice tests per release |
| Pre-auth over-approval or clinical harm | Low | High | Rules-first eligibility gate. LLM advisory below a value threshold. Reasoning trace plus appeal path. Provider notified within SLA regardless |
| Tier-1 hosted LLM outage or price change | Medium | Medium | Tier-2 self-hosted fallback per capability. Config-driven tier switch. Model card records both options |
| Fraud detection false-positive harming a legitimate provider | Medium | High | Explainable score with feature attribution. Two-person review before payment hold. Appeal path with a 5-day SLA |
| Pilot tenant delays post-challenge | Medium | Medium | Three-pilot pipeline (microinsurer plus liquidity-pressured short-term plus mid-tier medical aid). Any one landing keeps the roadmap alive |

## G.3 Product Readiness weak-submission self-check (per Product Readiness section 15)

| Weak-submission flag | Status |
|---|---|
| No clear user, beneficiary, or payer | Cleared. Seven user groups defined (section 1.3), four revenue lines defined (section 5.1) |
| No business or sustainability model | Cleared. Full canvas in Appendix D, cost projection in section 5.2, four-tier pricing model |
| No deployment plan beyond the demo | Cleared. Full plan in Appendix E, 90-day roadmap in section 3.1 |
| No repository, dashboard, notebook, or evidence pack | Cleared. Repository at https://github.com/MecksonMfema/medfund. Evidence pack in Appendices B and H |
| No explanation of backend, database, integrations, or architecture | Cleared. Section 2.2 plus Appendix A |
| No data source explanation | Cleared. Section 2.5 plus Appendix F |
| AI mentioned but not justified | Cleared. Section 2.3 three-tier framing plus 10 capability entries plus Appendix F |
| Screenshots polished but no working path | Cleared. Hosted demo URL live. Adjudicator smoke test in section 3.1 bootcamp criteria |
| Generic template with little local relevance | Cleared. Zim-specific content: AHFoZ tariff, ICD-10, ZiG, PSMAS, Promise Banda case, IPEC 2026 Amendment Act, SI 67 of 2025, POTRAZ Innovation Crucible |
| Security, privacy, and consent ignored | Cleared. Sections 4.1 to 4.4 plus DPA obligations in G.1 |
| Team unable to explain how the product works | Cleared. Lead Innovator authored the platform. Sector SME on team. DPO track named |
