package com.medfund.shared.report.regulatory;

import com.medfund.shared.report.ReportGenerationException;

/**
 * Regulatory-specific subclass of {@link ReportGenerationException}. Used
 * by {@code RegulatoryFxPolicy} + per-regulator shapers so a regulator
 * report failure is distinguishable from a generic report failure at
 * exception-handler and logging altitude. Behavioural semantics match
 * the parent: unrecoverable, maps to a 5xx-ish response.
 *
 * <p>Fail-loud only. Do not use for envelope warnings.
 */
public class RegulatoryReportGenerationException extends ReportGenerationException {
    public RegulatoryReportGenerationException(String message) {
        super(message);
    }
    public RegulatoryReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
