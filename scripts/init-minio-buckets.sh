#!/bin/sh
# Provisions MinIO buckets for the local compose stack. Wired via the
# `minio-init` service in docker-compose.yml — runs once per `make infra`,
# idempotent on repeat runs.
#
# Bucket layout:
#   medfund-report-payloads — Phase 15 §10 (I25) oversize report-job payloads.
#     Object naming: {jobId}-{chunkId}-{input|result}.json.
#
# Retention is entirely application-side. `ReportJobRetentionJob` (finance-service
# §3) purges parent `report_job` rows on the STATUTORY_7Y (7-year) or
# OPERATIONAL_90D (90-day) schedule, and the same cleanup pass deletes any
# `params_ref` / `result_ref` MinIO objects. A bucket-level ILM rule would need
# to know each object's parent retention class to be correct — that lives in
# Postgres, not in the object name — so no ILM rule is provisioned here.
#
# Uses the MinIO client (mc) inside the minio/mc image. MINIO_ENDPOINT /
# MINIO_ROOT_USER / MINIO_ROOT_PASSWORD are set by docker-compose.
set -eu

echo "[init-minio] waiting for MinIO to be reachable at ${MINIO_ENDPOINT}"
until mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" > /dev/null 2>&1; do
  sleep 1
done

echo "[init-minio] provisioning bucket: medfund-report-payloads"
mc mb --ignore-existing local/medfund-report-payloads

echo "[init-minio] done"
