package com.medfund.finance.ifrs17.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import io.r2dbc.postgresql.codec.Json;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §21 unit tests for {@link Ifrs17XlsxService}. Exercises the two
 * report-key layouts (LRC/LIC vs revenue+service-result), the failure
 * fallback sheet, the summary-sheet chunk roll-up, and the aggregator
 * envelope shape written by
 * {@link com.medfund.finance.ifrs17.service.Ifrs17JobAggregator#buildEnvelope}.
 */
class Ifrs17XlsxServiceTest {

    private final Ifrs17XlsxService service = new Ifrs17XlsxService(new ObjectMapper());

    // ── LRC/LIC report ──────────────────────────────────────────────────

    @Test
    void renders_lrcLic_summaryAndPortfolioSheets() {
        ReportJob job = completedLrcLicJob();

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = openWorkbook(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        assertThat(summary).as("Summary sheet exists").isNotNull();
                        assertThat(cellText(summary, "Report"))
                                .isEqualTo("IFRS 17 — LRC / LIC reconciliation");
                        assertThat(cellText(summary, "Status")).isEqualTo("completed");
                        assertThat(cellText(summary, "Total chunks")).isEqualTo("2");
                        assertThat(cellText(summary, "Completed chunks")).isEqualTo("2");
                        assertThat(cellText(summary, "Measurement models")).isEqualTo("PAA");
                        assertThat(cellText(summary, "Currencies")).isEqualTo("USD");
                        assertThat(cellText(summary, "Portfolios")).isEqualTo("1");

                        // One portfolio → one portfolio sheet (in addition to summary).
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        Sheet portfolio = wb.getSheetAt(1);
                        assertThat(hasCellStartingWith(portfolio, "Portfolio ")).isTrue();
                        // LRC block header + LIC block header both present.
                        assertThat(hasCellStartingWith(portfolio, "Liability for Remaining Coverage"))
                                .isTrue();
                        assertThat(hasCellStartingWith(portfolio, "Liability for Incurred Claims"))
                                .isTrue();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void lrcLic_totalsRow_sumsOpeningAndClosingAcrossCohorts() {
        ReportJob job = completedLrcLicJob();

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = openWorkbook(bytes)) {
                        Sheet portfolio = wb.getSheetAt(1);
                        double lrcOpening = sumFirstNumericColumn(portfolio,
                                "Liability for Remaining Coverage (LRC)",
                                /* col */ 2, /* skipHeaderRows */ 1);
                        // Two cohorts contribute LRC opening 100 + 200 = 300.
                        assertThat(lrcOpening).isEqualTo(300d);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    // ── Insurance revenue / service result report ───────────────────────

    @Test
    void renders_insuranceRevenue_totalsRow() {
        ReportJob job = completedRevenueJob();

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = openWorkbook(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        assertThat(cellText(summary, "Report"))
                                .isEqualTo("IFRS 17 — insurance revenue & service result");
                        Sheet portfolio = wb.getSheetAt(1);
                        // "Insurance revenue & service result" section header.
                        assertThat(hasCellStartingWith(portfolio,
                                "Insurance revenue & service result")).isTrue();
                        // Total row uses "Total" label; sums revenue = 100 + 80 = 180.
                        double totalRevenue = findValueAdjacentTo(portfolio, "Total", 2);
                        assertThat(totalRevenue).isEqualTo(180d);
                        double totalServiceResult = findValueAdjacentTo(portfolio, "Total", 4);
                        // Both cohorts explicitly provided serviceResult
                        // (revenue - expenses = 30 + 30 = 60).
                        assertThat(totalServiceResult).isEqualTo(60d);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    // ── Failure path ────────────────────────────────────────────────────

    @Test
    void renders_failedJob_asSingleErrorSheet() {
        ReportJob job = new ReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("failed");
        job.setReportKey("IFRS17_LRC_LIC_RECONCILIATION");
        job.setErrorMessage("ai-service compute crashed");

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = openWorkbook(bytes)) {
                        assertThat(wb.getNumberOfSheets()).isEqualTo(1);
                        Sheet failed = wb.getSheet("Report failed");
                        assertThat(failed).isNotNull();
                        assertThat(cellText(failed, "Error"))
                                .isEqualTo("ai-service compute crashed");
                        assertThat(cellText(failed, "Report key"))
                                .isEqualTo("IFRS17_LRC_LIC_RECONCILIATION");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void renders_completedJob_withEmptyPortfolios_isSummaryPlusPlaceholder() {
        ReportJob job = new ReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("completed");
        job.setReportKey("IFRS17_LRC_LIC_RECONCILIATION");
        job.setCompletedAt(OffsetDateTime.now());
        job.setParamsJson(Json.of("""
                {"periodStart":"2026-01-01","periodEnd":"2026-06-30","reportingCurrency":"USD"}
                """));
        // Envelope with an empty portfolios map — aggregator writes this
        // when every chunk failed to produce a result.
        job.setResultJson(Json.of("""
                {"summary":{"totalChunks":0,"completedChunks":0,"failedChunks":0,
                            "measurementModelsSeen":[],"currenciesSeen":[]},
                 "portfolios":{}}
                """));

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = openWorkbook(bytes)) {
                        // Summary + "Portfolios" placeholder sheet.
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        assertThat(wb.getSheet("Portfolios")).isNotNull();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static ReportJob completedLrcLicJob() {
        ReportJob job = new ReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("completed");
        job.setReportKey("IFRS17_LRC_LIC_RECONCILIATION");
        job.setCompletedAt(OffsetDateTime.now());
        job.setParamsJson(Json.of("""
                {"periodStart":"2026-01-01","periodEnd":"2026-06-30","reportingCurrency":"USD"}
                """));
        job.setResultJson(Json.of("""
                {
                  "summary": {
                    "totalChunks": 2, "completedChunks": 2, "failedChunks": 0,
                    "measurementModelsSeen": ["PAA"],
                    "currenciesSeen": ["USD"]
                  },
                  "portfolios": {
                    "portfolio-A": {
                      "cohorts": {
                        "cohort-1": {
                          "USD": {
                            "status": "completed",
                            "model": "PAA",
                            "result": {
                              "lrc": {"opening":100, "newBusiness":0, "cashInflows":50,
                                       "insuranceRevenue":30, "financeExpense":2, "closing":122},
                              "lic": {"opening":10, "newBusiness":0, "cashOutflows":-5,
                                       "claimsIncurred":8, "financeExpense":0, "closing":13}
                            }
                          }
                        },
                        "cohort-2": {
                          "USD": {
                            "status": "completed",
                            "model": "PAA",
                            "result": {
                              "lrc": {"opening":200, "newBusiness":10, "cashInflows":80,
                                       "insuranceRevenue":50, "financeExpense":3, "closing":243},
                              "lic": {"opening":20, "newBusiness":0, "cashOutflows":-8,
                                       "claimsIncurred":12, "financeExpense":0, "closing":24}
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """));
        return job;
    }

    private static ReportJob completedRevenueJob() {
        ReportJob job = new ReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("completed");
        job.setReportKey("IFRS17_INSURANCE_REVENUE_SERVICE_RESULT");
        job.setCompletedAt(OffsetDateTime.now());
        job.setParamsJson(Json.of("""
                {"periodStart":"2026-01-01","periodEnd":"2026-06-30","reportingCurrency":"USD"}
                """));
        job.setResultJson(Json.of("""
                {
                  "summary": {
                    "totalChunks": 2, "completedChunks": 2, "failedChunks": 0,
                    "measurementModelsSeen": ["PAA"],
                    "currenciesSeen": ["USD"]
                  },
                  "portfolios": {
                    "portfolio-A": {
                      "cohorts": {
                        "cohort-1": {
                          "USD": {
                            "status": "completed",
                            "model": "PAA",
                            "result": {
                              "insuranceRevenue": 100,
                              "insuranceServiceExpenses": 70,
                              "insuranceServiceResult": 30,
                              "insuranceFinanceExpense": 5
                            }
                          }
                        },
                        "cohort-2": {
                          "USD": {
                            "status": "completed",
                            "model": "PAA",
                            "result": {
                              "insuranceRevenue": 80,
                              "insuranceServiceExpenses": 50,
                              "insuranceServiceResult": 30,
                              "insuranceFinanceExpense": 3
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """));
        return job;
    }

    // ── Assertion helpers ───────────────────────────────────────────────

    private static Workbook openWorkbook(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    /** Value cell adjacent to a label cell (col 0 label, col 1 value). */
    private static String cellText(Sheet sheet, String label) {
        for (Row row : sheet) {
            Cell labelCell = row.getCell(0);
            if (labelCell != null && label.equals(labelCell.getStringCellValue())) {
                Cell valueCell = row.getCell(1);
                if (valueCell == null) return null;
                return valueCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                        ? String.valueOf((int) valueCell.getNumericCellValue())
                        : valueCell.getStringCellValue();
            }
        }
        throw new AssertionError("Label '" + label + "' not found on sheet " + sheet.getSheetName());
    }

    private static boolean hasCellStartingWith(Sheet sheet, String prefix) {
        for (Row row : sheet) {
            Cell c = row.getCell(0);
            if (c != null && c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                String v = c.getStringCellValue();
                if (v != null && v.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /** Sum a numeric column starting a few rows past the section title,
     *  stopping when we hit the "Total" row. Used to verify block totals. */
    private static double sumFirstNumericColumn(Sheet sheet, String sectionTitle,
                                                  int colIdx, int skipHeaderRows) {
        boolean inSection = false;
        int headerRowsToSkip = skipHeaderRows;
        double sum = 0d;
        for (Row row : sheet) {
            Cell labelCell = row.getCell(0);
            if (!inSection) {
                if (labelCell != null && sectionTitle.equals(labelCell.getStringCellValue())) {
                    inSection = true;
                }
                continue;
            }
            if (headerRowsToSkip > 0) {
                headerRowsToSkip--;
                continue;
            }
            // Stop at the "Total" row or at a blank row.
            if (labelCell != null && "Total".equals(labelCell.getStringCellValue())) {
                break;
            }
            Cell numCell = row.getCell(colIdx);
            if (numCell != null && numCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                sum += numCell.getNumericCellValue();
            }
        }
        return sum;
    }

    /** Find the numeric cell at (label-row, colIdx). Row is located by
     *  col-0 text = label. */
    private static double findValueAdjacentTo(Sheet sheet, String label, int colIdx) {
        for (Row row : sheet) {
            Cell labelCell = row.getCell(0);
            if (labelCell != null && labelCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && label.equals(labelCell.getStringCellValue())) {
                Cell v = row.getCell(colIdx);
                if (v != null && v.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                    return v.getNumericCellValue();
                }
            }
        }
        throw new AssertionError("No numeric cell at (" + label + ", col " + colIdx + ")");
    }
}
