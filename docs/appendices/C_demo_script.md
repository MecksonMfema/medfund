# Appendix C. Bootcamp Demo Script

Status at submission: outline. Filled in during bootcamp week 1 (27 Jul to 1 Aug 2026).

## Demo scope

Three-line demo on a seeded tenant. Health, motor, and funeral claims run end-to-end through intake, adjudication, and payout.

## Screens covered

- Adjudicator queue view with AI reasoning trace visible on borderline claims
- Tenant admin view of rules engine hot-reload
- Member Flutter app: enrolment with biometric KYC, claim photo submission, digital ID, status view
- Provider portal pre-auth request and response
- Live activity stream (WebSocket) into the adjudicator dashboard

## Endpoints exercised

To be listed against the bootcamp build. Includes `/claims`, `/preauth`, `/enrollments`, `/adjudicate`, `/audit-events`, `/ai-decisions`.

## Expected outputs

- Adjudication decision returned within 800 ms (rules-only) or 2,500 ms (with LLM triage)
- Every AI-assisted decision logged with model version, features, confidence, and outcome
- Every entity mutation logged with actor, actor email, correlation ID
- Payment-success rate 95% or higher on Ecocash test transactions
- ICD-10 F1 at 0.85 or higher on top-100 codes against a synthetic held-out set

## Full four-window roadmap table

Bootcamp, Days 0-30, Days 31-60, and Days 61-90 detail table lives here and mirrors the summary in section 3.1 of the proposal. Populated during bootcamp with actual definitions of done for each window.

Placeholder for the styled roadmap table.
