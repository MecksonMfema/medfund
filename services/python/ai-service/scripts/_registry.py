"""Shared artifact-registry helpers for the Tranche 1 model pipeline.

MinIO layout (per grilling decision G6):

    medfund-ml-artifacts/
        fraud-health-v1.joblib
        fraud-health-v1.metadata.json
        fraud-vehicle-v2.joblib
        fraud-vehicle-v2.metadata.json
        manifest.json          — {"fraud/HEALTH": "v1", ...}

Object naming: ``{model_type}-{line-lower}-{version}.joblib`` — enforced by
``object_name_for`` so the trainer, evaluator, resolver, and admin API all
agree on the layout.

All I/O routes through the existing ``minio`` client wired for report
payloads (``app.kafka.minio_payload._get_client``). Tests inject a fake
via ``_ml_client()`` monkey-patching.
"""
from __future__ import annotations

import io
import json
import logging
import os
from dataclasses import dataclass
from typing import Any

from minio import Minio
from minio.error import S3Error

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine

logger = logging.getLogger(__name__)


MANIFEST_MISSING_SENTINEL = "__no_manifest__"

_client: Minio | None = None


def _ml_client() -> Minio:
    """Lazy-init the MinIO client for the ml-artifacts bucket.

    Kept separate from ``app.kafka.minio_payload._get_client`` so tests can
    inject a fake here without affecting Kafka payload IO, but the credential
    env vars are shared with the rest of the platform.
    """
    global _client
    if _client is None:
        endpoint = os.environ.get("MINIO_ENDPOINT", "localhost:9000")
        endpoint = endpoint.replace("http://", "").replace("https://", "")
        try:
            access_key = os.environ["MINIO_ACCESS_KEY"]
            secret_key = os.environ["MINIO_SECRET_KEY"]
        except KeyError as e:
            raise RuntimeError(
                f"MinIO credential env var missing: {e.args[0]} — set it before "
                "reading or writing artifacts."
            ) from e
        secure = os.environ.get("MINIO_SECURE", "false").lower() == "true"
        _client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
    return _client


def _reset_client_for_tests() -> None:
    """Testing hook — force the next ``_ml_client()`` call to re-read env."""
    global _client
    _client = None


def object_name_for(model_type: str, line: InsuranceLine | str, version: str) -> str:
    """Filename inside the bucket. Line values are lowercased in the object key."""
    line_value = line.value if isinstance(line, InsuranceLine) else line
    return f"{model_type}-{line_value.lower()}-{version}.joblib"


def metadata_name_for(model_type: str, line: InsuranceLine | str, version: str) -> str:
    return object_name_for(model_type, line, version).replace(".joblib", ".metadata.json")


def s3_ref(model_type: str, line: InsuranceLine | str, version: str) -> str:
    return f"s3://{settings.model_artifacts_bucket}/{object_name_for(model_type, line, version)}"


@dataclass
class ArtifactUpload:
    model_type: str
    line: InsuranceLine
    version: str
    joblib_bytes: bytes
    metadata: dict[str, Any]

    def object_name(self) -> str:
        return object_name_for(self.model_type, self.line, self.version)

    def metadata_name(self) -> str:
        return metadata_name_for(self.model_type, self.line, self.version)


def upload_artifact(upload: ArtifactUpload) -> str:
    """Upload joblib bytes + metadata sidecar. Returns the ``s3://`` ref."""
    bucket = settings.model_artifacts_bucket
    client = _ml_client()
    joblib_key = upload.object_name()
    metadata_key = upload.metadata_name()
    try:
        client.put_object(
            bucket, joblib_key,
            io.BytesIO(upload.joblib_bytes),
            length=len(upload.joblib_bytes),
            content_type="application/octet-stream",
        )
        metadata_bytes = json.dumps(upload.metadata, indent=2).encode("utf-8")
        client.put_object(
            bucket, metadata_key,
            io.BytesIO(metadata_bytes),
            length=len(metadata_bytes),
            content_type="application/json",
        )
    except S3Error as e:
        raise RuntimeError(
            f"MinIO upload failed for {joblib_key}: {e}"
        ) from e
    logger.info(
        "Uploaded artifact %s (%d bytes) + metadata to bucket %s",
        joblib_key, len(upload.joblib_bytes), bucket,
    )
    return f"s3://{bucket}/{joblib_key}"


def download_artifact(model_type: str, line: InsuranceLine, version: str) -> bytes:
    """Download the joblib bytes for a specific version."""
    bucket = settings.model_artifacts_bucket
    key = object_name_for(model_type, line, version)
    response = _ml_client().get_object(bucket, key)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def download_metadata(model_type: str, line: InsuranceLine, version: str) -> dict[str, Any]:
    """Download and JSON-parse the metadata sidecar."""
    bucket = settings.model_artifacts_bucket
    key = metadata_name_for(model_type, line, version)
    response = _ml_client().get_object(bucket, key)
    try:
        return json.loads(response.read().decode("utf-8"))
    finally:
        response.close()
        response.release_conn()


def load_manifest() -> dict[str, str]:
    """Return the current ``{model_type}/{LINE} -> version`` map.

    An absent manifest is treated as empty (fresh install / bucket
    just-created); callers should not distinguish "no manifest" from
    "empty manifest".
    """
    bucket = settings.model_artifacts_bucket
    try:
        response = _ml_client().get_object(bucket, settings.model_manifest_key)
    except S3Error as e:
        if getattr(e, "code", "") in {"NoSuchKey", "NoSuchBucket"}:
            return {}
        raise
    try:
        return json.loads(response.read().decode("utf-8"))
    finally:
        response.close()
        response.release_conn()


def save_manifest(manifest: dict[str, str]) -> None:
    """Overwrite the manifest with the given mapping."""
    bucket = settings.model_artifacts_bucket
    payload = json.dumps(manifest, indent=2, sort_keys=True).encode("utf-8")
    _ml_client().put_object(
        bucket,
        settings.model_manifest_key,
        io.BytesIO(payload),
        length=len(payload),
        content_type="application/json",
    )


def manifest_key(model_type: str, line: InsuranceLine | str) -> str:
    """Composite key used inside manifest.json — ``"{model_type}/{LINE}"``."""
    line_value = line.value if isinstance(line, InsuranceLine) else line
    return f"{model_type}/{line_value}"


def active_version(model_type: str, line: InsuranceLine) -> str | None:
    """Return the manifest's currently-active version for a (model_type, line)."""
    return load_manifest().get(manifest_key(model_type, line))


def promote_manifest_entry(
    model_type: str, line: InsuranceLine, version: str,
) -> tuple[str | None, str]:
    """Update the manifest atomically. Returns ``(before, after)``.

    ``before`` is ``None`` when no version was previously active for this
    (model_type, line) tuple.
    """
    manifest = load_manifest()
    key = manifest_key(model_type, line)
    before = manifest.get(key)
    manifest[key] = version
    save_manifest(manifest)
    return before, version


def artifact_exists(model_type: str, line: InsuranceLine, version: str) -> bool:
    """Cheap check that both the joblib and its metadata exist for a version."""
    bucket = settings.model_artifacts_bucket
    joblib_key = object_name_for(model_type, line, version)
    metadata_key = metadata_name_for(model_type, line, version)
    try:
        _ml_client().stat_object(bucket, joblib_key)
        _ml_client().stat_object(bucket, metadata_key)
        return True
    except S3Error:
        return False
