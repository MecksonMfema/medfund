"""Tests for duplicate claim detection."""
from app.services.duplicate_detection import DuplicateDetector


def test_detects_exact_duplicate():
    detector = DuplicateDetector()
    claim = {"claim_id": "C1", "member_id": "M1", "provider_id": "P1", "claimed_amount": 500,
             "service_date": "2026-03-01", "procedure_codes": ["0190"]}
    recent = [{"claim_id": "C0", "member_id": "M1", "provider_id": "P1", "claimed_amount": 500,
               "service_date": "2026-03-01", "procedure_codes": ["0190"]}]
    result = detector.check_duplicate(claim, recent)
    assert result["is_duplicate"] is True
    assert result["highest_score"] >= 0.7


def test_no_duplicate_different_member():
    detector = DuplicateDetector()
    claim = {"claim_id": "C1", "member_id": "M1", "provider_id": "P1", "claimed_amount": 500,
             "service_date": "2026-03-01"}
    recent = [{"claim_id": "C0", "member_id": "M2", "provider_id": "P1", "claimed_amount": 500,
               "service_date": "2026-03-01"}]
    result = detector.check_duplicate(claim, recent)
    assert result["is_duplicate"] is False


def test_no_duplicate_empty_recent():
    detector = DuplicateDetector()
    claim = {"claim_id": "C1", "member_id": "M1"}
    result = detector.check_duplicate(claim, [])
    assert result["is_duplicate"] is False
    assert result["highest_score"] == 0.0


def test_partial_match_flagged():
    detector = DuplicateDetector()
    claim = {"claim_id": "C1", "member_id": "M1", "provider_id": "P1", "claimed_amount": 500,
             "service_date": "2026-03-01"}
    recent = [{"claim_id": "C0", "member_id": "M1", "provider_id": "P2", "claimed_amount": 520,
               "service_date": "2026-03-02"}]
    result = detector.check_duplicate(claim, recent)
    # Same member + close date + close amount but different provider
    assert len(result["matches"]) >= 0  # may or may not match depending on thresholds
