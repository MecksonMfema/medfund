# Appendix H. Testing Evidence Pack

Status at submission: baseline. Grows across the 90-day pilot with real screenshots, logs, and validation reports.

## Test coverage summary

- Unit tests. JUnit 5 for Java, `testing` plus `testify` for Go, ExUnit for Elixir, pytest for Python. Coverage target 70% or higher on business-logic packages.
- Integration tests. Testcontainers (PostgreSQL, Kafka, Redis, Keycloak, WireMock) with tenant-isolation, multi-currency, and audit-trail guard-rails committed.
- Load. k6 at 10,000 claims per hour sustained. P99 adjudication latency under 800 ms rules-only, under 2,500 ms with LLM triage.
- Model unit tests. Per-model precision, recall, and AUC on synthetic plus first-tenant hold-out. Bias slice tests on gender (where lawful), province, provider tier, and language before every release.

## Full maturity matrix

| Component | At submission | Bootcamp (27 Jul to 1 Aug) | End of 90-day pilot |
|---|---|---|---|
| Claims plus six-stage adjudication | Full on 3 lines (health, motor, funeral) | Plus life | Full 6 lines |
| Provider Network | Partial (onboarding plus tariff live, pre-auth scaffolded) | Pre-auth workflow live | Full |
| Finance and Regulatory | Partial. Reports operational, full GL plus reserves in-flight | Reports demonstrable | Full GL plus reserves plus IPEC return generator |
| Audit trail (Kafka to append-only ledger) | Functional and improving. Actor email, entity name, correlation ID enrichment extending | Plus AI-decision stream (section 4.2) | Full replay plus regulator export API |
| Engagement live activity stream | Stubbed | MVP WebSocket (Elixir and Phoenix) | Full fan-out across 7 portals |
| Payment gateway integrations | Design plus stubs. Orchestrator abstraction in place | Ecocash live in demo | Plus OneMoney, Paynow, Zimswitch and POS, RTGS bulk-EFT |
| Data, Analytics, and Insight | Event stream plus 3 KPI endpoints | 5 operational KPI endpoints live | Full analytics per section 2.4 |
| AI Service. Three-tier selection (section 2.3) | Open. Some capabilities stubbed. Tier per capability is a bootcamp or pilot decision | AI-1, AI-4, AI-5, AI-7 wired with candidate tier | Tier decisions finalised. Custom models trained on first-tenant data |
| Portals (Provider, Group Liaison, Regulator) | Provider partial. Group Liaison scaffolded. Regulator design | Provider full. Group Liaison MVP | Full |
| Flutter member app (offline-first) | Core screens working. Biometric-KYC in-flight | Enrolment plus claim-photo plus status live | Full offline sync, digital ID, appeal flow |

## Evidence artefacts

To be added during Days 0 to 30. CI badge screenshots, `gradle test` and `pytest` output, k6 load-test report, model precision-recall curves, DPA audit sign-off note.
