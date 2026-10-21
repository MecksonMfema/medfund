"""Phase 0.5 unit tests — anonymization audit gate + corpus sample redaction.

Covers:

- ``require_training_audit_signoff`` exits with code 3 when the env var is
  absent (this is the actual gate that Phase 2's train_fraud.py depends on).
- Corpus sample dump strips `reviewed_by*`, `_review_feedback`, and any
  non-allowlisted output keys — column contract enforced in code, not
  just documented in the audit doc.
- `created_at` is truncated to month.
"""
from __future__ import annotations

from datetime import UTC, datetime

import pytest

from app.models.db_models import AIPredictionDB
from scripts import _audit_gate
from scripts.dump_corpus_sample import _filter_output, _to_row, _truncate_to_month

# ── Audit gate ──────────────────────────────────────────────────────────────


def test_audit_gate_open_when_env_true(monkeypatch):
    monkeypatch.setenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF", "true")
    assert _audit_gate.is_training_audit_signed_off() is True


def test_audit_gate_closed_when_env_absent(monkeypatch):
    monkeypatch.delenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF", raising=False)
    monkeypatch.setattr(_audit_gate.settings, "training_audit_signed_off", False)
    assert _audit_gate.is_training_audit_signed_off() is False


@pytest.mark.parametrize("val", ["", "false", "0", "no", "off"])
def test_audit_gate_closed_for_falsy_strings(monkeypatch, val):
    monkeypatch.setenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF", val)
    monkeypatch.setattr(_audit_gate.settings, "training_audit_signed_off", False)
    assert _audit_gate.is_training_audit_signed_off() is False


def test_require_exits_when_gate_closed(monkeypatch, capsys):
    monkeypatch.delenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF", raising=False)
    monkeypatch.setattr(_audit_gate.settings, "training_audit_signed_off", False)
    with pytest.raises(SystemExit) as exc:
        _audit_gate.require_training_audit_signoff(script_name="test_case")
    assert exc.value.code == _audit_gate.EXIT_CODE_AUDIT_MISSING
    err = capsys.readouterr().err
    assert "audit" in err.lower()
    assert "test_case" in err


def test_require_passes_when_gate_open(monkeypatch):
    monkeypatch.setenv("MEDFUND_TRAINING_AUDIT_SIGNED_OFF", "true")
    _audit_gate.require_training_audit_signoff(script_name="test_case")


# ── Corpus dump redaction ───────────────────────────────────────────────────


def test_truncate_to_month_naive():
    dt = datetime(2026, 3, 15, 14, 22, 33, 123)
    assert _truncate_to_month(dt) == "2026-03-01T00:00:00+00:00"


def test_truncate_to_month_none():
    assert _truncate_to_month(None) is None


def test_filter_output_keeps_only_allowlist():
    raw = {
        "risk_level": "HIGH",
        "risk_score": 0.9,
        "canonical_features": [1, 2, 3, 4, 5],
        "line_features": {"diagnosis_codes": ["A01"]},
        "_review_feedback": "member said X",       # dropped
        "recommendation": "APPROVE",                # dropped (not in allowlist)
        "confidence": 0.9,                          # dropped (top-level field, not output)
    }
    out = _filter_output(raw)
    assert set(out.keys()) == {
        "risk_level", "risk_score", "canonical_features", "line_features",
    }
    assert "_review_feedback" not in out
    assert "recommendation" not in out


def test_to_row_drops_reviewer_identity_and_input_features():
    pred = AIPredictionDB(
        id="p1",
        tenant_id="tenant-A",
        insurance_line="HEALTH",
        entity_type="claim",
        entity_id="claim-1",
        prediction_type="fraud",
        model_version="fraud-canonical",
        input_features={"member_email_shouldnt_leak": "leaked@x.com"},
        output={
            "risk_level": "HIGH",
            "canonical_features": [100.0, 30.0, 2, 0, 0],
            "_review_feedback": "sensitive free text",
        },
        confidence=0.7,
        accepted=True,
        reviewed_by="user-1",
        reviewed_by_email="reviewer@example.com",
        reviewed_at=datetime(2026, 3, 15, tzinfo=UTC),
        created_at=datetime(2026, 3, 15, 14, 22, 33, tzinfo=UTC),
    )
    row = _to_row(pred)
    assert row["tenant_id"] == "tenant-A"
    assert row["insurance_line"] == "HEALTH"
    assert row["output"]["risk_level"] == "HIGH"
    assert "_review_feedback" not in row["output"]
    assert "reviewed_by" not in row
    assert "reviewed_by_email" not in row
    assert "reviewed_at" not in row
    assert "entity_id" not in row  # claim identifier — not in allowlist
    assert "input_features" not in row  # not in Tranche 1 preview scope
    assert row["created_at_month"] == "2026-03-01T00:00:00+00:00"
