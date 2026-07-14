# Appendix F. Data and AI Usage Note

Status at submission: initial content. Expands per model release and per tenant onboarding.

## Data sources (extends section 2.5 of the proposal)

| Data category | Source | Status | Lawful basis |
|---|---|---|---|
| Synthetic claim, policy, and provider seed (around 50k records, 6 lines) | Team-generated (faker plus GAN augmentation) | `/data/synthetic/` | Public or synthetic |
| ICD-10 catalogue | WHO public release | `/data/icd10/` | Public domain |
| AHFoZ tariff mechanism | Structural docs public. Live schedules member-only per tenant contract | Structural docs public | Contract per tenant |
| Motor, property, life, funeral exemplars | Public court records plus synthesised | `/data/exemplars/` | Public or synthetic |
| Real tenant data (pilot) | Pilot insurer, DPA consent plus processor agreement | Onboarded post-bootcamp | Contract plus informed consent |
| Fraud case exemplars | Public reporting (Promise Banda, EcoSure, PSMAS) | `/data/fraud_cases/` | Public reporting |
| Weather and climate (agri line) | Meteorological Services Dept, satellite | Roadmap post-pilot | Public API |

Synthetic data validation: Kolmogorov-Smirnov and chi-squared correlation tests on age, geography, claim amount, and provider mix. Report at `/validation/synthetic_validation_v1.pdf`.

## Per-capability model cards

Model cards for AI-1 through AI-10 live under `/services/python/ai-service/model_cards/`. Each card carries model family, tier, training-data description, evaluation metrics on synthetic and first-tenant hold-out, bias slice-test results, and last-updated timestamp. See section 2.3 of the proposal for the three-tier framing (Tier 1 hosted APIs, Tier 2 self-hosted open-weight, Tier 3 custom).

## Validation methods

- Precision, recall, and AUC per model on synthetic plus first-tenant hold-out
- Bias slice tests on gender (where lawful), province, provider tier, language, and age band before every release
- Retraining events versioned in the audit trail (`ai-decisions` topic, section 4.2 of the proposal)
- Human review threshold configurable per tenant

## Human oversight

Every high-value or contested decision retains a human-in-the-loop. Reason codes render in the member's language. One-tap escalation in the Flutter app. DPO oversight over model releases.
