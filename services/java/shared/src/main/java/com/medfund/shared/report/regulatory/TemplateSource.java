package com.medfund.shared.report.regulatory;

/**
 * Provenance of a loaded regulatory template. Angular uses this to decide
 * whether to render the "synthetic template — verify against your regulator's
 * current template before filing" banner on the report page.
 */
public enum TemplateSource {

    /** Tenant admin uploaded an override via {@code public.tenant_regulatory_template}. */
    TENANT_OVERRIDE,

    /** Real regulator XLSX bundled in the shared module. */
    BUNDLED_REAL,

    /** Hand-drawn synthetic approximation (regulator portal-locked); needs review before filing. */
    BUNDLED_SYNTHETIC
}
