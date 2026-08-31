package com.medfund.shared.report.regulatory;

/**
 * A {@code (sheet, rowLabel, columnHeader)} locator for a cell in a bundled
 * regulator template. Used as a fallback when a template does not carry a
 * named range for the field we need to write.
 *
 * <p>The matcher pairs row identity (exact case-insensitive equality against
 * column A after trim) with column identity (exact case-insensitive equality
 * against the first row after trim). The first hit wins if either label
 * repeats — real regulator templates typically have unique labels within a
 * sheet, so this is rare in practice.
 *
 * <p>Prefer a named range when the template already carries one — named
 * ranges survive row/column inserts by the regulator, anchor lookups do not.
 */
public record LabelAnchor(String sheet, String rowLabel, String columnHeader) {

    public LabelAnchor {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("sheet name is required");
        }
        if (rowLabel == null || rowLabel.isBlank()) {
            throw new IllegalArgumentException("rowLabel is required");
        }
        if (columnHeader == null || columnHeader.isBlank()) {
            throw new IllegalArgumentException("columnHeader is required");
        }
    }
}
