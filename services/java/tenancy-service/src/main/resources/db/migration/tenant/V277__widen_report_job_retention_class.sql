-- V277: widen report_job.retention_class to admit all four classes.
--
-- ReportJob defines four retention classes (ReportJob.java: OPERATIONAL_90D,
-- STATUTORY_7Y, FRAUD_FLAG_1Y, SIU_CASE_7Y) and all four are written from main
-- code (ReportJobRetentionJob, Ifrs17JobService). V251 only permitted two, so
-- inserting FRAUD_FLAG_1Y or SIU_CASE_7Y violates report_job_retention_class_ck.
--
-- Per the never-edit-an-applied-migration rule this is a new higher-numbered
-- migration (current max V276), not an edit to V251. Idempotent: drop-if-exists
-- then re-add.
ALTER TABLE report_job DROP CONSTRAINT IF EXISTS report_job_retention_class_ck;
ALTER TABLE report_job ADD CONSTRAINT report_job_retention_class_ck
    CHECK (retention_class IN ('OPERATIONAL_90D','STATUTORY_7Y','FRAUD_FLAG_1Y','SIU_CASE_7Y'));
