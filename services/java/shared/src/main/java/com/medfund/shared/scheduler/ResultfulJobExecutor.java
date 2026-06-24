package com.medfund.shared.scheduler;

import reactor.core.publisher.Mono;

/**
 * Sub-interface for executors that hand back a JSON payload on success
 * (e.g. ad-hoc billing preview/commit). The dispatcher stores the payload
 * into {@code scheduled_job_runs.result_payload} so the UI can fetch it by
 * polling the runs endpoint. Executors that don't produce a payload should
 * keep implementing the plain {@link JobExecutor} interface.
 */
public interface ResultfulJobExecutor extends JobExecutor {

    /**
     * Execute the job and return a JSON result payload. Return
     * {@link Mono#empty()} for "ran successfully but no payload" — the run
     * row will still be marked SUCCESS with a null result_payload.
     */
    Mono<String> executeAndCapture(String tenantId, String settings);

    /**
     * The plain-interface adapter — delegates to {@link #executeAndCapture}
     * and discards the payload. Keeps the {@link JobDispatcher} polymorphic
     * over both interfaces without an extra runtime check.
     */
    @Override
    default Mono<Void> execute(String tenantId, String settings) {
        return executeAndCapture(tenantId, settings).then();
    }
}
