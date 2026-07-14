# Appendix E. Deployment Plan

Also appended to the AI4I proposal PDF after the body. This file is the repo copy for adjudicators who prefer browsing the code repository.

## Deployment environment

Hybrid cloud. Primary hosting on ZCHPC HPC Cloud Account and KVM VPS for training, inference, and OLTP workloads. Helm chart parity for fallback deployment to any Kubernetes 1.29 or newer cluster. Flutter mobile app ships through Google Play, Apple App Store, and as a Progressive Web App with offline-first behaviour.

## Hosting provider or site

- Primary. ZCHPC HPC Cloud Account for GPU-backed training. ZCHPC KVM VPS for CPU-backed inference and services.
- Secondary. Managed Kubernetes from any regional provider (AWS Cape Town, Azure South Africa, or on-premise) via the Helm chart at `/deploy/helm/`.
- Storage. PostgreSQL 17 primary plus streaming replica plus daily object-storage backup (S3-compatible). Object storage for file attachments and analytics-store snapshots.
- Model artefacts. Model weights and LoRA adapters versioned in an S3-compatible object store with SHA-pinned references from the AI service.

## Operator

Post-challenge operational ownership rests with the Lead Innovator as accountable steward. The team of 5 handles first-line support during the 90-day pilot window. A short-form services agreement signs with each pilot tenant before any tenant data flows. Named DPO from Day 0 (per section 4.1 of the proposal).

## Pilot site

Pilot 1 site: a microinsurance provider in Zimbabwe, finalised at bootcamp. Deployment happens in a sandbox tenant on ZCHPC before promotion to a production tenant behind Keycloak-authenticated access. 500 anonymised historical claims process end-to-end before real member data flows.

## Users to onboard

Pilot 1 first-30-day cohort. 3 to 5 insurer adjudicators, 1 tenant admin, 1 compliance officer, and 20 to 50 member app test users. Group Liaison and Regulator portals onboard in Days 31 to 90 as those modules complete (per section 3.1 of the proposal).

## Training and support

- Adjudicator and tenant-admin training. Two 90-minute video sessions plus a written runbook per module. Shared Slack or Teams channel for real-time questions.
- Provider training. In-clinic session per participating provider on pre-auth submission through the Provider portal.
- Member training. In-app walkthrough plus SMS onboarding messages in English, Shona, and Ndebele.
- Support. Weekday email plus WhatsApp channel with a 4-hour response SLA on P1 incidents, 24-hour on P2, 3-business-day on P3.

## Monitoring

- Application logs to Loki via OpenTelemetry.
- Distributed traces to Tempo.
- Metrics to Mimir with dashboards for adjudication TAT, backlog, complaint rate, pre-auth turnaround, payment-success rate by PSP, and per-model precision and recall over rolling windows.
- Uptime probes on every public API endpoint plus a synthetic transaction every 5 minutes against the pilot tenant.
- AI-decision audit topic (`ai-decisions`) plus entity audit topic (`audit-events`) feed the append-only ledger (per section 4.2 of the proposal).
- Incident on-call rotation across the team.

## Backup and recovery

- PostgreSQL. Continuous WAL archiving to S3 plus a nightly base backup plus a 30-day point-in-time-recovery window.
- Kafka. 7-day retention on business-event topics plus tiered storage for audit topics.
- Object storage. Cross-region replication to a second bucket.
- Recovery drill quarterly. Documented RPO under 5 minutes, RTO under 4 hours for the OLTP path.
- Every tenant gets a standards-compliant export on request (DPA portability per section 4.1 of the proposal).

## Connectivity plan

- Flutter app runs offline-first. Claim intake queues to encrypted local SQLite and syncs on reconnect through idempotent server-side dedup.
- SMS and USSD fallback for members without reliable data.
- Digital-ID QR renders offline.
- Low-bandwidth Angular portal build (deferred routes, image compression, service-worker cache) for adjudicators and providers on constrained links.
- Payment gateway integrations use provider-agnostic switching so a failing PSP falls back to the next configured channel within 10 seconds.

## Scale pathway

Pilot 1 microinsurer runs Days 0 to 90. Pilot 2 short-term insurer with liquidity pressure runs Months 4 to 6. Pilot 3 mid-tier medical-aid society runs Months 7 to 12. TPA or broker onboarding in Months 10 to 14 to prove the module-as-a-service motion. Full Zimbabwe rollout targets 10 full-platform and 5 module-only tenants by Month 12 (per section 5.2 of the proposal cost model). SADC Phase 2 (South Africa, Botswana, Zambia, Namibia) targets Months 12 to 24. Regulator route: IPEC first, then ZICB, then NBFIRA (Botswana), FSCA (South Africa), NAMFISA (Namibia), PIA (Zambia).

## Milestones

Days 0 to 30. Pilot tenant sandbox live. 500 anonymised claims processed end-to-end. ICD-10 F1 at 0.85 or higher on top-100 codes. KYC false-accept at 0.5% or lower on liveness test set. DPO named. POTRAZ controller licence submitted.

Days 31 to 60. Fraud model in shadow mode on real tenant data. Fraud AUC at 0.80 or higher. Pre-auth shadow agreement with human at 80% or higher. Chatbot cites policy clause on 90% or more of test cases. DPA compliance audit closed. Payment-success at 95% or higher across Ecocash plus OneMoney plus Paynow.

Days 61 to 90. Shadow-mode adjudication agreement with human adjudicators at 85% or higher. Measured claim TAT improvement at 50% or higher on shadowed claims. Pre-auth turnaround under 10 minutes. First IPEC quarterly return auto-generated end-to-end. First regulator briefing to IPEC.
