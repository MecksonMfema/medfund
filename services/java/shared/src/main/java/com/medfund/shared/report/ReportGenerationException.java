package com.medfund.shared.report;

/**
 * Thrown when a report cannot be generated because a required input is
 * missing — most commonly a fail-loud FX rate lookup that has no matching
 * row. Distinct from {@link IllegalArgumentException} so controllers can
 * map it to a 502-like response (upstream data problem) instead of a 400
 * (bad user input).
 *
 * <p>Fail-loud only. Best-effort envelope-population failures are handled
 * via {@link ReportResponse#warnings()} without throwing.
 */
public class ReportGenerationException extends RuntimeException {
    public ReportGenerationException(String message) {
        super(message);
    }
    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
