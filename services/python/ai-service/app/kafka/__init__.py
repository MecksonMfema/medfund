"""Shared Kafka-adjacent helpers for the ai-service compute pipeline.

Phase 15 §10 (I25) introduced MinIO-backed fallback for oversize report-job
payloads; ``minio_payload`` is the Python side of that shared helper. The
Java counterpart lives at ``com.medfund.shared.kafka.MinIOPayloadStore``.
"""
