"""MinIO oversize-payload fallback for report-job Kafka events.

Symmetric with ``com.medfund.shared.kafka.MinIOPayloadStore``. Phase 15 §10
(I25) — a chunk request or result that exceeds Kafka's default 1 MB message
ceiling is streamed to the ``medfund-report-payloads`` bucket, and the Kafka
envelope carries an ``s3://<bucket>/<key>`` reference the consumer resolves
on the far side.

Size policy:

* ``size <= SIZE_WARN`` — inline. ``maybe_upload_*`` returns ``None``.
* ``SIZE_WARN < size <= SIZE_LIMIT`` — inline but a warning is logged so
  operators see payloads approaching the ceiling.
* ``size > SIZE_LIMIT`` — object is uploaded and the ``s3://`` ref returned.

Environment:

* ``MINIO_ENDPOINT`` — host:port (defaults to ``localhost:9000``). Scheme
  prefix is stripped if present.
* ``MINIO_ACCESS_KEY`` / ``MINIO_SECRET_KEY`` — required to construct the
  client. A missing key trips ``RuntimeError`` at first use, not at import
  time — services that never dispatch IFRS 17 chunks stay bootable without
  MinIO creds.
* ``MINIO_BUCKET_REPORT_PAYLOADS`` — bucket name; defaults to
  ``medfund-report-payloads``.
* ``MINIO_SECURE`` — ``true`` for TLS; defaults to ``false`` for the local
  compose stack.

Client construction is lazy so unit tests can inject a fake by monkey-patching
``_get_client``.
"""

from __future__ import annotations

import io
import logging
import os
from typing import Optional

from minio import Minio
from minio.error import S3Error

log = logging.getLogger(__name__)

SIZE_WARN = 800 * 1024
SIZE_LIMIT = 900 * 1024

DEFAULT_BUCKET = "medfund-report-payloads"

_client: Optional[Minio] = None


def _bucket() -> str:
    return os.environ.get("MINIO_BUCKET_REPORT_PAYLOADS", DEFAULT_BUCKET)


def _get_client() -> Minio:
    """Lazy-init the MinIO client. Callable overridable in tests."""
    global _client
    if _client is None:
        endpoint = os.environ.get("MINIO_ENDPOINT", "localhost:9000")
        # `Minio(endpoint=...)` rejects a scheme; strip if the operator passed one.
        endpoint = endpoint.replace("http://", "").replace("https://", "")
        try:
            access_key = os.environ["MINIO_ACCESS_KEY"]
            secret_key = os.environ["MINIO_SECRET_KEY"]
        except KeyError as e:
            raise RuntimeError(
                f"MinIO credential env var missing: {e.args[0]} — set it before "
                "calling maybe_upload_* or download."
            ) from e
        secure = os.environ.get("MINIO_SECURE", "false").lower() == "true"
        _client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
    return _client


def _reset_client_for_tests() -> None:
    """Testing hook — force the next _get_client() call to re-read env."""
    global _client
    _client = None


def maybe_upload_input(job_id: str, chunk_id: str, payload_bytes: bytes) -> Optional[str]:
    """Upload chunk-request payload to MinIO if oversize; return ref or None."""
    return _maybe_upload(job_id, chunk_id, payload_bytes, "input")


def maybe_upload_result(job_id: str, chunk_id: str, payload_bytes: bytes) -> Optional[str]:
    """Upload chunk-result payload to MinIO if oversize; return ref or None."""
    return _maybe_upload(job_id, chunk_id, payload_bytes, "result")


def _maybe_upload(job_id: str, chunk_id: str, payload_bytes: bytes, kind: str) -> Optional[str]:
    size = len(payload_bytes)
    if SIZE_WARN < size <= SIZE_LIMIT:
        log.warning(
            "Payload for job %s chunk %s (%s) nearing Kafka ceiling: %d KB",
            job_id, chunk_id, kind, size // 1024,
        )
        return None
    if size <= SIZE_LIMIT:
        return None

    bucket = _bucket()
    key = f"{job_id}-{chunk_id}-{kind}.json"
    try:
        _get_client().put_object(
            bucket,
            key,
            io.BytesIO(payload_bytes),
            length=size,
            content_type="application/json",
        )
        log.info(
            "Uploaded oversize %s payload for job=%s chunk=%s → s3://%s/%s (%d KB)",
            kind, job_id, chunk_id, bucket, key, size // 1024,
        )
        return f"s3://{bucket}/{key}"
    except S3Error as e:
        raise RuntimeError(f"MinIO upload failed for {key}: {e}") from e


def download(payload_ref: str) -> bytes:
    """Fetch object bytes referenced by ``s3://{bucket}/{key}``."""
    bucket = _bucket()
    prefix = f"s3://{bucket}/"
    if not payload_ref or not payload_ref.startswith(prefix):
        raise ValueError(
            f"payload_ref does not target bucket {bucket}: {payload_ref!r}"
        )
    key = payload_ref[len(prefix):]
    try:
        response = _get_client().get_object(bucket, key)
    except S3Error as e:
        raise RuntimeError(f"MinIO download failed for {key}: {e}") from e
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def delete(payload_ref: str) -> None:
    """Best-effort delete — errors are logged, never raised."""
    bucket = _bucket()
    prefix = f"s3://{bucket}/"
    if not payload_ref or not payload_ref.startswith(prefix):
        log.warning("delete: payload_ref does not target bucket %s: %r", bucket, payload_ref)
        return
    key = payload_ref[len(prefix):]
    try:
        _get_client().remove_object(bucket, key)
    except S3Error as e:
        log.warning("MinIO delete failed for %s: %s", key, e)
