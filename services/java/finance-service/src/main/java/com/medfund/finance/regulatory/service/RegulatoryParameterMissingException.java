package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;

/**
 * Thrown by {@link RegulatoryParameterResolver} when neither the tenant's
 * rules-engine {@code REGULATORY_PARAMETER} rules nor the bundled YAML
 * defaults yield a value for a parameter — a template-authoring gap that
 * must not silently zero a report cell on a live regulator submission.
 *
 * <p>Extends {@link RegulatoryReportGenerationException} so per-regulator
 * controllers' existing 500 handler catches it uniformly.
 */
public class RegulatoryParameterMissingException extends RegulatoryReportGenerationException {

    public RegulatoryParameterMissingException(String message) {
        super(message);
    }
}
