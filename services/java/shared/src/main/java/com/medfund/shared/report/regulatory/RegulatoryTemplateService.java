package com.medfund.shared.report.regulatory;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads bundled regulator XLSX templates from the classpath and writes cell
 * values into them by named-range (primary) or {@link LabelAnchor} lookup
 * (fallback).
 *
 * <p>Templates live under
 * {@code shared/src/main/resources/report-templates/{regulator}/{reportKey}-v_{yyyy-MM-dd}.xlsx}.
 * The {@code -v_SYNTHETIC_} version marker means the template is a synthetic
 * approximation and Angular shows a banner on the report page; it does not
 * affect selection order.
 *
 * <p>Phase 3 ships only the bundled path. Phase 4 extends {@code load(...)} to
 * consult tenant overrides in {@code public.tenant_regulatory_template} first
 * and fall back here.
 */
@Slf4j
@Service
public class RegulatoryTemplateService {

    /**
     * Matches {@code {reportKey}-v_[SYNTHETIC_]YYYY-MM-DD.xlsx}. Group 1 is the
     * synthetic marker (present or empty); group 2 is the ISO date.
     */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile(".*-v_(SYNTHETIC_)?(\\d{4}-\\d{2}-\\d{2})\\.xlsx$", Pattern.CASE_INSENSITIVE);

    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    /**
     * Optional tenant-override reader — {@code Optional} because the reader
     * requires a live {@link org.springframework.r2dbc.core.DatabaseClient},
     * and unit tests of the bundled-only path shouldn't have to bring one up.
     * Production Spring context always wires it because
     * {@link TenantRegulatoryTemplateOverrideReader} is a {@code @Component}.
     */
    private final Optional<TenantRegulatoryTemplateOverrideReader> overrideReader;

    /** No-arg constructor for unit tests that only need the bundled path. */
    public RegulatoryTemplateService() {
        this.overrideReader = Optional.empty();
    }

    @Autowired
    public RegulatoryTemplateService(Optional<TenantRegulatoryTemplateOverrideReader> overrideReader) {
        this.overrideReader = overrideReader;
    }

    // ── Resolution ─────────────────────────────────────────────────────────────

    /**
     * Resolve a template for {@code (tenantId, regulator, reportKey, effectiveDate)}
     * — tries a tenant-uploaded override first, falls back to the bundled path.
     * The returned {@link TemplateResolution} carries the opened workbook plus
     * its provenance ({@link TemplateSource}) and a version label; Angular renders
     * a warning banner when {@code source == BUNDLED_SYNTHETIC}.
     *
     * <p>The caller owns workbook lifecycle — call {@code toBytes(...)} for
     * response bodies or close explicitly.
     */
    public Mono<TemplateResolution> load(UUID tenantId, String regulator, String reportKey, LocalDate effectiveDate) {
        Mono<Optional<TenantRegulatoryTemplateOverrideReader.Overlay>> overlayLookup = overrideReader
                .map(reader -> reader.findEffective(tenantId, regulator, reportKey, effectiveDate).map(Optional::of))
                .orElseGet(() -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty());

        return overlayLookup.map(maybe -> {
            if (maybe.isPresent()) {
                TenantRegulatoryTemplateOverrideReader.Overlay overlay = maybe.get();
                try {
                    Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(overlay.xlsxBytes()));
                    return new TemplateResolution(wb, TemplateSource.TENANT_OVERRIDE, overlay.versionLabel());
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to open tenant regulatory template override for tenant "
                                    + tenantId + " " + regulator + "/" + reportKey, e);
                }
            }
            // Fall back to bundled — resolveBundledResource returns the classpath
            // path (which carries the SYNTHETIC marker if applicable).
            String resourcePath = resolveBundledResource(regulator, reportKey, effectiveDate)
                    .orElseThrow(() -> new IllegalStateException(
                            "No bundled regulator template found for regulator=" + regulator
                                    + " reportKey=" + reportKey + " effective=" + effectiveDate));
            Workbook wb = openClasspathWorkbook(resourcePath);
            TemplateSource source = isSynthetic(resourcePath) ? TemplateSource.BUNDLED_SYNTHETIC : TemplateSource.BUNDLED_REAL;
            String versionLabel = extractVersionLabel(resourcePath);
            return new TemplateResolution(wb, source, versionLabel);
        });
    }

    /**
     * Load a bundled template for {@code (regulator, reportKey, effectiveDate)}.
     * Picks the highest version ≤ {@code effectiveDate}; throws when nothing
     * matches so the caller gets a clear error rather than a silent empty run.
     */
    public Workbook loadBundled(String regulator, String reportKey, LocalDate effectiveDate) {
        String resourcePath = resolveBundledResource(regulator, reportKey, effectiveDate)
                .orElseThrow(() -> new IllegalStateException(
                        "No bundled regulator template found for regulator=" + regulator
                                + " reportKey=" + reportKey + " effective=" + effectiveDate));
        return openClasspathWorkbook(resourcePath);
    }

    private Workbook openClasspathWorkbook(String resourcePath) {
        try (InputStream in = resolver.getResource("classpath:" + resourcePath).getInputStream()) {
            return new XSSFWorkbook(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open bundled regulator template: " + resourcePath, e);
        }
    }

    /** Extract the {@code YYYY-MM-DD} portion (with {@code SYNTHETIC_} prefix stripped) from a bundled path. */
    static String extractVersionLabel(String resourcePath) {
        if (resourcePath == null) return null;
        Matcher m = VERSION_PATTERN.matcher(resourcePath);
        if (!m.matches()) return null;
        String synthetic = m.group(1);
        String date = m.group(2);
        return synthetic != null ? "SYNTHETIC_" + date : date;
    }

    /**
     * Return the classpath path of the highest-versioned bundled template for
     * {@code (regulator, reportKey)} whose version date is ≤ {@code effectiveDate}.
     * Empty when nothing matches; caller decides how to react (loadBundled throws;
     * Phase 4's overlay-aware load falls back further).
     */
    public Optional<String> resolveBundledResource(String regulator, String reportKey, LocalDate effectiveDate) {
        if (regulator == null || regulator.isBlank() || reportKey == null || reportKey.isBlank()
                || effectiveDate == null) {
            return Optional.empty();
        }
        // classpath*: enumerates matches across every jar on the classpath — plain
        // classpath: only resolves a single root, missing bundled templates that
        // ship inside the shared jar when a consumer service runs (Phase 10 caught
        // this against the IPEC bundle).
        String pattern = "classpath*:report-templates/" + regulator + "/" + reportKey + "-v*.xlsx";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("[regulatory-template] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new java.util.ArrayList<>(resources.length);
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(regulator, filenames, effectiveDate);
    }

    /**
     * Pure-function seam: given the filenames of candidate templates for a
     * regulator, pick the one with the highest version date ≤ {@code effectiveDate}.
     * Package-private so unit tests can exercise without a classpath scan.
     */
    static Optional<String> pickHighestVersion(String regulator, List<String> filenames, LocalDate effectiveDate) {
        LocalDate best = null;
        String bestPath = null;
        for (String filename : filenames) {
            Matcher m = VERSION_PATTERN.matcher(filename);
            if (!m.matches()) continue;
            LocalDate v;
            try {
                v = LocalDate.parse(m.group(2), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                log.warn("[regulatory-template] unparseable version date in {}: {}", filename, e.getMessage());
                continue;
            }
            if (v.isAfter(effectiveDate)) continue;
            if (best == null || v.isAfter(best)) {
                best = v;
                bestPath = "report-templates/" + regulator + "/" + filename;
            }
        }
        return Optional.ofNullable(bestPath);
    }

    /** True when the resource filename carries the {@code SYNTHETIC} version marker. */
    public static boolean isSynthetic(String resourcePath) {
        if (resourcePath == null) return false;
        Matcher m = VERSION_PATTERN.matcher(resourcePath);
        return m.matches() && m.group(1) != null;
    }

    // ── Cell writes ────────────────────────────────────────────────────────────

    /** Dispatch a single mapping to the matching write mechanism. */
    public void write(Workbook wb, RegulatoryCellMap.Mapping<?> mapping, Object value) {
        Objects.requireNonNull(wb, "workbook");
        Objects.requireNonNull(mapping, "mapping");
        if (mapping instanceof RegulatoryCellMap.NamedRange<?> nr) {
            writeNamed(wb, nr.rangeName(), value);
        } else if (mapping instanceof RegulatoryCellMap.Anchored<?> a) {
            writeAnchored(wb, a.anchor(), value);
        }
    }

    /**
     * Write a value into the (single-cell) named range. Throws when the range
     * is missing or spans more than one cell — either is a template-authoring
     * bug that should fail loud rather than silently drop the value.
     */
    public void writeNamed(Workbook wb, String rangeName, Object value) {
        Objects.requireNonNull(wb, "workbook");
        Objects.requireNonNull(rangeName, "rangeName");
        Name name = wb.getName(rangeName);
        if (name == null) {
            throw new IllegalStateException("Named range not found in template: " + rangeName);
        }
        String formula = name.getRefersToFormula();
        // Named ranges take the form "SheetName!$A$1" (single cell) or
        // "SheetName!$A$1:$B$3" (range). We only support single-cell ranges —
        // multi-cell ranges are typically tables and need row-wise writes.
        if (formula.contains(":")) {
            throw new IllegalStateException(
                    "Named range " + rangeName + " spans multiple cells (" + formula
                            + "); only single-cell named ranges are supported for direct writes");
        }
        AreaTarget target = parseSingleCellFormula(formula);
        Sheet sheet = wb.getSheet(target.sheet);
        if (sheet == null) {
            throw new IllegalStateException("Named range " + rangeName + " points at unknown sheet: " + target.sheet);
        }
        Row row = sheet.getRow(target.row);
        if (row == null) row = sheet.createRow(target.row);
        Cell cell = row.getCell(target.col);
        if (cell == null) cell = row.createCell(target.col);
        setCellValue(cell, value);
    }

    /**
     * Write a value into the cell located by the given anchor. Match is
     * case-insensitive after trim; the first hit on both axes wins.
     * Throws on unknown sheet, unmatched row label, or unmatched column header.
     */
    public void writeAnchored(Workbook wb, LabelAnchor anchor, Object value) {
        Objects.requireNonNull(wb, "workbook");
        Objects.requireNonNull(anchor, "anchor");
        Sheet sheet = wb.getSheet(anchor.sheet());
        if (sheet == null) {
            throw new IllegalStateException("Sheet not found: " + anchor.sheet());
        }
        int headerRowIdx = sheet.getFirstRowNum();
        Row headerRow = sheet.getRow(headerRowIdx);
        if (headerRow == null) {
            throw new IllegalStateException("Sheet " + anchor.sheet() + " has no header row");
        }
        int colIdx = findColumnByHeader(headerRow, anchor.columnHeader());
        if (colIdx < 0) {
            throw new IllegalStateException("Column header not found in sheet " + anchor.sheet()
                    + ": " + anchor.columnHeader());
        }
        int rowIdx = findRowByLabel(sheet, anchor.rowLabel(), headerRowIdx);
        if (rowIdx < 0) {
            throw new IllegalStateException("Row label not found in sheet " + anchor.sheet()
                    + ": " + anchor.rowLabel());
        }
        Row row = sheet.getRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        setCellValue(cell, value);
    }

    /**
     * Fill every mapping declared by the cell map with a value pulled from
     * {@code data}. Missing entries in the data map are skipped — regulator
     * templates commonly leave optional sections blank.
     */
    public <K extends Enum<K>> void fill(Workbook wb, RegulatoryCellMap<K> cellMap, Map<K, Object> data) {
        Objects.requireNonNull(wb, "workbook");
        Objects.requireNonNull(cellMap, "cellMap");
        Objects.requireNonNull(data, "data");
        List<RegulatoryCellMap.Mapping<K>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<K> m : mappings) {
            Object value = data.get(m.key());
            if (value == null) continue;
            write(wb, m, value);
        }
    }

    /** Serialise the workbook to a byte array for HTTP response bodies. */
    public byte[] toBytes(Workbook wb) {
        Objects.requireNonNull(wb, "workbook");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise workbook", e);
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private record AreaTarget(String sheet, int row, int col) {}

    private static AreaTarget parseSingleCellFormula(String formula) {
        // "'Sheet Name'!$A$1" or "Sheet!A1"
        int bang = formula.indexOf('!');
        if (bang < 0) {
            throw new IllegalStateException("Named range formula missing sheet: " + formula);
        }
        String sheetPart = formula.substring(0, bang);
        String cellPart  = formula.substring(bang + 1);
        // Strip surrounding quotes around sheet names containing spaces
        if (sheetPart.startsWith("'") && sheetPart.endsWith("'")) {
            sheetPart = sheetPart.substring(1, sheetPart.length() - 1).replace("''", "'");
        }
        CellReference ref = new CellReference(cellPart);
        return new AreaTarget(sheetPart, ref.getRow(), ref.getCol());
    }

    private static int findColumnByHeader(Row headerRow, String header) {
        String needle = header.trim();
        for (Cell cell : headerRow) {
            String text = readCellAsText(cell);
            if (text != null && text.trim().equalsIgnoreCase(needle)) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private static int findRowByLabel(Sheet sheet, String label, int headerRowIdx) {
        String needle = label.trim();
        int last = sheet.getLastRowNum();
        for (int i = headerRowIdx + 1; i <= last; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Cell first = row.getCell(0);
            String text = readCellAsText(first);
            if (text != null && text.trim().equalsIgnoreCase(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String readCellAsText(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> Double.toString(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    /**
     * Coerce the caller-supplied value into the most sensible POI cell type.
     * BigDecimal / Number → numeric, LocalDate / Instant / Date → date-typed
     * numeric with default date style if unset, Boolean → boolean, everything
     * else → toString().
     */
    private static void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return;
        }
        if (value instanceof LocalDate ld) {
            cell.setCellValue(java.sql.Date.valueOf(ld));
            return;
        }
        if (value instanceof Instant i) {
            cell.setCellValue(Date.from(i));
            return;
        }
        if (value instanceof Date d) {
            cell.setCellValue(d);
            return;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        cell.setCellValue(value.toString());
    }
}
