# Appendix B. GitHub Repository Layout and README

The root `README.md` at the top of this repository is the authoritative entry point. Direct link:

- https://github.com/OWNER/REPO/blob/main/README.md

Replace `OWNER/REPO` with the actual URL at submission time.

## Repository layout (top level)

- `clients/angular`. Angular 19 web portals (Super Admin, Tenant Admin, Adjudicator, Provider, Group Liaison, Regulator)
- `clients/flutter`. Flutter 3.24 member app (Android, iOS, Progressive Web App, offline-first)
- `services/java`. Java 21 Spring Boot 3.3 WebFlux services (tenancy, user, claims, contributions, finance, rules-engine, policy admin, provider network)
- `services/go`. Go 1.23 Fiber v2 services (gateway, notification, audit, file, payment-gateway)
- `services/elixir`. Elixir 1.17 Phoenix 1.7 umbrella (live dashboard, chat)
- `services/python/ai-service`. Python 3.12 FastAPI AI service running the three-tier model stack (per section 2.3 of the proposal)
- `docs`. Architecture and process documentation
- `docs/appendices`. Appendices A through J for the AI4I proposal
- `proposals`. AI4I proposal source (`AI4I_Track3_Proposal_Draft.md` and rendered `.docx`)
- `deploy/helm`. Helm chart for Kubernetes 1.29 or newer deployment
- `.github/workflows`. CI pipelines producing green builds and SBOM per commit

## What the README covers

- Problem statement and target users
- Solution overview and modular consumption model
- Setup and demo instructions (local Docker Compose plus hosted URL)
- Architecture pointers (Appendix A for the full render)
- Data and AI usage overview (Appendix F for the full note)
- Testing and CI status
- Known limitations (per section 2.6 maturity matrix and Appendix H)
- Team and licence

## Access for judges

Per Product Readiness section 6, repositories are public or private, and judges need access before assessment. The repo is either public during the judging window (14 Jul to 1 Aug 2026) or private with judge accounts invited as read-only collaborators. State the choice on the AI4I portal submission form.
