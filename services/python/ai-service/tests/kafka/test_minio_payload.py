"""Unit tests for app.kafka.minio_payload.

Uses a fake Minio client (monkey-patching ``_get_client``) so the boundary
policy + ref-shape contract are pinned deterministically without a real
MinIO server. The Testcontainers-backed round-trip lives in a separate IT
outside this fast suite.
"""

from __future__ import annotations

import io
from typing import Any
from unittest.mock import MagicMock

import pytest
from minio.error import S3Error

from app.kafka import minio_payload


@pytest.fixture(autouse=True)
def _env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MINIO_ENDPOINT", "minio:9000")
    monkeypatch.setenv("MINIO_ACCESS_KEY", "medfund")
    monkeypatch.setenv("MINIO_SECRET_KEY", "medfund123")
    monkeypatch.setenv("MINIO_BUCKET_REPORT_PAYLOADS", "medfund-report-payloads")
    minio_payload._reset_client_for_tests()


@pytest.fixture
def fake_client(monkeypatch: pytest.MonkeyPatch) -> MagicMock:
    client = MagicMock()
    monkeypatch.setattr(minio_payload, "_get_client", lambda: client)
    return client


def test_maybe_upload_input_below_warn_returns_none(fake_client: MagicMock) -> None:
    ref = minio_payload.maybe_upload_input("job-1", "chunk-1", b"x" * (100 * 1024))
    assert ref is None
    fake_client.put_object.assert_not_called()


def test_maybe_upload_input_at_size_limit_stays_inline(fake_client: MagicMock) -> None:
    payload = b"x" * minio_payload.SIZE_LIMIT
    ref = minio_payload.maybe_upload_input("job-1", "chunk-1", payload)
    assert ref is None
    fake_client.put_object.assert_not_called()


def test_maybe_upload_input_between_warn_and_limit_stays_inline_but_logs(
    fake_client: MagicMock, caplog: pytest.LogCaptureFixture
) -> None:
    payload = b"x" * (850 * 1024)  # inside the warn band
    with caplog.at_level("WARNING", logger="app.kafka.minio_payload"):
        ref = minio_payload.maybe_upload_input("job-1", "chunk-1", payload)
    assert ref is None
    fake_client.put_object.assert_not_called()
    assert any("nearing Kafka ceiling" in rec.message for rec in caplog.records)


def test_maybe_upload_input_over_limit_uploads_and_returns_ref(fake_client: MagicMock) -> None:
    payload = b"x" * (minio_payload.SIZE_LIMIT + 1)

    ref = minio_payload.maybe_upload_input("job-1", "chunk-1", payload)

    assert ref == "s3://medfund-report-payloads/job-1-chunk-1-input.json"
    call = fake_client.put_object.call_args
    args, kwargs = call.args, call.kwargs
    assert args[0] == "medfund-report-payloads"
    assert args[1] == "job-1-chunk-1-input.json"
    # third positional is the BytesIO stream
    assert kwargs["length"] == len(payload)
    assert kwargs["content_type"] == "application/json"


def test_maybe_upload_result_uses_result_suffix(fake_client: MagicMock) -> None:
    payload = b"y" * (minio_payload.SIZE_LIMIT + 1)

    ref = minio_payload.maybe_upload_result("job-1", "chunk-1", payload)

    assert ref == "s3://medfund-report-payloads/job-1-chunk-1-result.json"


def test_maybe_upload_input_wraps_s3error(fake_client: MagicMock) -> None:
    payload = b"z" * (minio_payload.SIZE_LIMIT + 1)
    fake_client.put_object.side_effect = S3Error(
        code="InternalError",
        message="disk full",
        resource="job-1-chunk-1-input.json",
        request_id="rid",
        host_id="hid",
        response=MagicMock(status=500),
    )

    with pytest.raises(RuntimeError) as exc_info:
        minio_payload.maybe_upload_input("job-1", "chunk-1", payload)
    assert "MinIO upload failed" in str(exc_info.value)


def test_download_returns_bytes(fake_client: MagicMock) -> None:
    stub_response = MagicMock()
    stub_response.read.return_value = b"payload-bytes"
    fake_client.get_object.return_value = stub_response

    result = minio_payload.download("s3://medfund-report-payloads/job-1-chunk-1-input.json")

    assert result == b"payload-bytes"
    fake_client.get_object.assert_called_once_with(
        "medfund-report-payloads", "job-1-chunk-1-input.json"
    )
    stub_response.close.assert_called_once()
    stub_response.release_conn.assert_called_once()


def test_download_rejects_wrong_bucket_ref(fake_client: MagicMock) -> None:
    with pytest.raises(ValueError):
        minio_payload.download("s3://some-other-bucket/foo.json")
    fake_client.get_object.assert_not_called()


def test_download_rejects_non_s3_ref(fake_client: MagicMock) -> None:
    with pytest.raises(ValueError):
        minio_payload.download("http://example.com/blob")


def test_download_wraps_s3error(fake_client: MagicMock) -> None:
    fake_client.get_object.side_effect = S3Error(
        code="NoSuchKey",
        message="gone",
        resource="missing.json",
        request_id="rid",
        host_id="hid",
        response=MagicMock(status=404),
    )

    with pytest.raises(RuntimeError) as exc_info:
        minio_payload.download("s3://medfund-report-payloads/missing.json")
    assert "MinIO download failed" in str(exc_info.value)


def test_delete_swallows_s3error(fake_client: MagicMock) -> None:
    fake_client.remove_object.side_effect = S3Error(
        code="NoSuchKey",
        message="gone",
        resource="x.json",
        request_id="rid",
        host_id="hid",
        response=MagicMock(status=404),
    )

    # Must not raise — retention job is best-effort.
    minio_payload.delete("s3://medfund-report-payloads/x.json")


def test_delete_ignores_wrong_bucket_ref(fake_client: MagicMock) -> None:
    minio_payload.delete("s3://wrong-bucket/x.json")
    fake_client.remove_object.assert_not_called()


def test_get_client_strips_scheme(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, Any] = {}

    class FakeMinio:
        def __init__(self, endpoint: str, **kwargs: Any) -> None:
            captured["endpoint"] = endpoint
            captured["kwargs"] = kwargs

    monkeypatch.setattr(minio_payload, "Minio", FakeMinio)
    monkeypatch.setenv("MINIO_ENDPOINT", "https://minio.example.com:9000")
    minio_payload._reset_client_for_tests()

    minio_payload._get_client()

    assert captured["endpoint"] == "minio.example.com:9000"
    assert captured["kwargs"]["secure"] is False


def test_get_client_raises_when_credentials_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("MINIO_ACCESS_KEY", raising=False)
    monkeypatch.delenv("MINIO_SECRET_KEY", raising=False)
    minio_payload._reset_client_for_tests()

    with pytest.raises(RuntimeError, match="MINIO_ACCESS_KEY"):
        minio_payload._get_client()
