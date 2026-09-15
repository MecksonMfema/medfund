"""Tests for the PII anonymization helper."""
from app.core.anonymize import anonymize_features


def test_strips_direct_identifiers():
    result = anonymize_features({
        "member_id": "abc",
        "subject_id": "def",
        "provider_id": "ghi",
        "claim_id": "jkl",
        "correlation_id": "mno",
        "user_id": "pqr",
        "conversation_id": "stu",
        "asset_id": "vwx",
        "vehicle_id": "yz1",
        "property_id": "234",
        "member_name": "Alice",
        "provider_name": "Beta Clinic",
        "member_email": "alice@example.com",
        "provider_email": "beta@example.com",
        "claimed_amount": 1500.0,
    })
    assert result == {"claimed_amount": 1500.0}


def test_preserves_non_id_fields():
    assert anonymize_features({
        "claimed_amount": 1500.0,
        "diagnosis_codes": ["K35"],
        "notes": "some description",
    }) == {
        "claimed_amount": 1500.0,
        "diagnosis_codes": ["K35"],
        "notes": "some description",
    }


def test_recurses_into_nested_dicts():
    result = anonymize_features({
        "context": {"member_id": "abc", "extra": 5},
        "note": "hello",
    })
    assert result == {"context": {"extra": 5}, "note": "hello"}


def test_recurses_into_list_of_dicts():
    result = anonymize_features({
        "history": [
            {"member_id": "abc", "amount": 10},
            {"member_id": "def", "amount": 20},
        ],
    })
    assert result == {"history": [{"amount": 10}, {"amount": 20}]}


def test_non_dict_input_returns_empty():
    assert anonymize_features("hello") == {}
    assert anonymize_features(None) == {}
    assert anonymize_features(42) == {}
    assert anonymize_features(["list"]) == {}
