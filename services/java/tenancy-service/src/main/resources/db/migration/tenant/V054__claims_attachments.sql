-- =====================================================================
-- V054: claims.attachments_json (attachment metadata)
-- =====================================================================
-- Captures the list of documents the operator attached at submission —
-- scanned receipts, incident photos, medical letters, police reports.
-- Stored as a JSON array of {filename, contentType, sizeBytes} because
-- the schema is stable but rows without attachments are the common case
-- (an ad-hoc claim needs no supporting docs).
--
-- Byte storage lives out-of-band: today the metadata is captured but the
-- upload path to MinIO is deferred until file-service moves off its
-- MockStorage backend. This column keeps the shape correct so the
-- upload wiring can land without another migration.
-- =====================================================================

ALTER TABLE claims
    ADD COLUMN IF NOT EXISTS attachments_json TEXT;
