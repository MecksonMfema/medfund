package com.medfund.shared.report.regulatory;

import org.apache.poi.ss.usermodel.Workbook;

/**
 * Result of resolving a regulatory template for a
 * {@code (tenant, regulator, reportKey, effectiveDate)}: the opened
 * {@link Workbook} plus its provenance and a human-facing version label.
 *
 * <p>Callers own the workbook lifecycle — call {@code workbook().close()}
 * or serialise via {@link RegulatoryTemplateService#toBytes(Workbook)}.
 */
public record TemplateResolution(
        Workbook workbook,
        TemplateSource source,
        String versionLabel
) {
    public boolean isSynthetic() {
        return source == TemplateSource.BUNDLED_SYNTHETIC;
    }

    public boolean isTenantOverride() {
        return source == TemplateSource.TENANT_OVERRIDE;
    }
}
