"""In-memory MinIO stand-in used by Phase 2 unit tests.

Enough of the ``minio.Minio`` API to exercise ``scripts._registry``:
- ``put_object`` / ``get_object`` / ``stat_object`` on a plain dict.
- Raises ``minio.error.S3Error`` with ``code='NoSuchKey'`` on misses.

Tests wire it in by monkey-patching ``scripts._registry._ml_client``.
"""
from __future__ import annotations

import io
from dataclasses import dataclass, field
from typing import Any

from minio.error import S3Error


class _NoSuchKeyError(S3Error):
    def __init__(self, key: str) -> None:
        # Bypass the S3Error __init__ signature — the tests only inspect .code.
        Exception.__init__(self, f"NoSuchKey: {key}")
        self.code = "NoSuchKey"
        self.message = f"NoSuchKey: {key}"
        self.resource = key
        self.request_id = ""
        self.host_id = ""


@dataclass
class _StoredObject:
    data: bytes
    content_type: str


@dataclass
class _Response:
    payload: bytes

    def read(self) -> bytes:
        return self.payload

    def close(self) -> None:
        pass

    def release_conn(self) -> None:
        pass


@dataclass
class FakeMinioClient:
    """In-memory replacement — keyed by ``(bucket, key)`` tuple."""
    objects: dict[tuple[str, str], _StoredObject] = field(default_factory=dict)

    def put_object(
        self,
        bucket: str,
        key: str,
        data: io.BytesIO,
        length: int,
        content_type: str = "application/octet-stream",
    ) -> None:
        payload = data.read() if hasattr(data, "read") else bytes(data)
        self.objects[(bucket, key)] = _StoredObject(
            data=payload, content_type=content_type,
        )

    def get_object(self, bucket: str, key: str) -> _Response:
        stored = self.objects.get((bucket, key))
        if stored is None:
            raise _NoSuchKeyError(key)
        return _Response(payload=stored.data)

    def stat_object(self, bucket: str, key: str) -> Any:
        if (bucket, key) not in self.objects:
            raise _NoSuchKeyError(key)
        return object()

    def remove_object(self, bucket: str, key: str) -> None:
        self.objects.pop((bucket, key), None)
