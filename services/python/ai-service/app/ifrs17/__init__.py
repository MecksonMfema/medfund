"""IFRS 17 compute package (Phase 15 §11+).

Sub-modules land per phase:

* ``types`` — Pydantic input/output envelopes shared by every compute path.
* ``paa`` (§11) — Premium Allocation Approach LRC/LIC compute.
* ``envelope`` — helpers for the per-currency / per-cohort / per-portfolio
  rollup shape defined by I13; populated as §17/§18 orchestration lands.
* ``gmm`` (§12+), ``discount_curve`` (§12), ``csm`` + ``coverage_units``
  (§13), ``risk_adjustment`` (§14), ``onerous_test`` (§15), ``vfa`` (§16)
  — stubbed here; §12+ ships each.

The Kafka runner in ``app.report.kafka`` dispatches on ``measurement_model``
(``PAA``/``GMM``/``VFA``) resolved by the ``IFRS17_MODEL`` rules-engine
category (§9) and passed through the request event's ``ifrs17_json`` slot.
"""
