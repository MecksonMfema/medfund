# Appendix D. Business Model Canvas

Also appended to the AI4I proposal PDF after the body. This file is the repo copy for adjudicators who prefer browsing the code repository.

## Customer Segments

Insurance carriers: 21 IPEC short-term insurers, 12 life assurers, 8 funeral assurers, 9 microinsurers, 10 reinsurers, and 36 AHFoZ medical-aid societies, roughly 122 addressable buyers in Zimbabwe. Third-party administrators, managing general agents, and brokers. Insurer operations, adjudicators, and underwriters. Healthcare providers, motor assessors, and funeral directors. Corporate groups, employer schemes, and group liaisons. Policyholders and dependants (2.12 m life, 1.63 m medical-aid, 151,578 microinsurance policies in Zimbabwe). Regulators (IPEC and the emerging ZICB).

## Value Propositions

- Full core insurance operating system in one platform. Replaces spreadsheets, WhatsApp, paper, and legacy silos.
- Rules-first adjudication with AI where rules fall short. Explainable pricing, explainable denials, human-in-loop on every high-value decision.
- Cross-lifecycle fraud detection. Application, provider, and claim signals combined. Targets 30 to 40% leakage across Zimbabwean claims.
- Multi-currency and multi-line native. USD and ZiG handled correctly across every ledger entry.
- Regulator-ready. Every mutation and every AI decision logged immutably. Auto-generated IPEC returns.
- Multilingual member service. English, Shona, and Ndebele conversational assistant with RAG over the tenant's own policy documents.
- Modular consumption. Full-platform, module-only, TPA delegated tenancy, embedded partner API, or regulator read-only.

## Channels

Direct sales to insurers and medical-aid societies through the Lead Innovator and sector SME. Regulator introductions via IPEC and AHFoZ. Ecosystem partnerships with POTRAZ, ZCHPC, and the National Innovation Acceleration Centre. Embedded-insurance API for telcos, banks, mobile-money operators, and e-commerce platforms. GitHub-hosted evidence pack and hosted demo for inbound interest.

## Customer Relationships

Named customer success manager per tenant. Quarterly business review. Shared Slack or Teams channel for day-to-day support. Public roadmap with tenant voting. Community of practice across pilot tenants. Regulator briefings every 90 days.

## Revenue Streams

Four lines. Platform subscription in four tiers by active policy count: T0 microinsurer USD 1,500 to 3,000 per month, T1 small carrier USD 4,000 to 8,000 per month, T2 mid-market USD 12,000 to 20,000 per month, T3 large carrier USD 35,000 to 60,000 per month. Module-as-a-Service USD 2,000 to 6,000 per module per month, minimum bundle USD 3,000. Fraud recovery share, 15% of documented pre-payment fraud prevented, capped at 100% of monthly subscription. Regulator API priced at regulator level for IPEC and ZICB.

## Key Resources

Zim-based engineering team of 5. Zim-hosted ZCHPC HPC and KVM VPS compute. Per-tenant Keycloak zones and PostgreSQL 17 schemas. Apache Kafka event backbone. Tier-3 custom models trained on pilot-tenant data. Model cards and audit-trail infrastructure. GitHub repository under version-locked manifests and SBOM per build.

## Key Activities

Product engineering across 6 domain modules plus AI service. Custom-model training and re-training on rolling analytics windows. Compliance pipeline: DPA controller licence, POTRAZ Innovation Crucible registration, bias slice tests per model release. Pilot onboarding and adoption support. Regulator engagement and quarterly briefings. Continuous integration, SBOM generation, and provenance attestation via cosign.

## Key Partnerships

POTRAZ (regulator and AI Grand Challenge sponsor). ZCHPC (compute and Innovation Crucible sandbox). IPEC and AHFoZ (sector regulators, pilot introductions). ZICB (emerging insurance crime bureau). Payment service providers (Ecocash, OneMoney, InnBucks, Paynow, Zimswitch, RTGS). AHFoZ tariff owners under per-tenant contract. Model providers where Tier-1 hosted APIs win a capability (OpenAI, Google).

## Cost Structure

Per full-platform tenant at 10,000 active policies. Compute (ZCHPC HPC plus KVM VPS) USD 800 to 1,800 per month. LLM inference (Tier 1 tokens or Tier 2 self-hosted GPU) USD 400 to 2,000 per month. Managed Postgres, backups, DR, monitoring USD 300 per month. Shared support USD 400 per month. Total delivery cost around USD 1,900 to 4,500 per tenant per month against a T2 subscription of around USD 15,000. Gross margin around 70 to 85%.

Shared platform costs. Engineering team salaries. Compliance and DPO retainer. Model training GPU-hours on ZCHPC (around 150 GPU-hours per 90-day cycle self-hosted, 40 GPU-hours if hosted APIs cover the LLM tier). CI infrastructure. Security tooling (Snyk, Dependabot, cosign, syft). Incident response.
