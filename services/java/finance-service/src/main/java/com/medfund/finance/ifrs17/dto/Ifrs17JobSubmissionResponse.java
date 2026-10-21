package com.medfund.finance.ifrs17.dto;

import java.util.UUID;

/**
 * Response body for the two IFRS 17 submit endpoints on
 * {@link com.medfund.finance.ifrs17.controller.Ifrs17ReportController}.
 *
 * @param jobId       the parent {@code report_job.job_id}
 * @param status      terminal-or-processing status of the parent
 * @param chunkCount  number of {@code report_job_chunk} rows fanned out
 * @param deduped     true when the params_hash matched an in-flight parent
 *                    from a prior submit — the same jobId is returned so the
 *                    caller can poll the existing job
 */
public record Ifrs17JobSubmissionResponse(
        UUID jobId,
        String status,
        int chunkCount,
        boolean deduped) {
}
