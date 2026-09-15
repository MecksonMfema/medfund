"""Offline scripts for the AI service.

Tranche 1 (see `thoughts/shared/plans/2026-09-15-ai-service-tranche-1-model-training.md`):

- `dump_corpus_sample.py` — anonymized 100-row preview for the Phase 0.5 audit.
- `train_fraud.py` — Phase 2. Refuses to run unless the audit gate is flipped.
- `evaluate_model.py` — Phase 2. Compares a candidate against `latest`.
- `promote_model.py` — CLI fallback to the UI promote flow (Phase 5).
"""
