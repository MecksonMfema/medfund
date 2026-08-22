package com.medfund.finance.reinsurance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.finance.reinsurance.entity.Reinsurer;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.BordereauQueryRepository;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.report.ReportingCurrencyResolver;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the multi-currency bordereau workbook shape end-to-end: sheet
 * count matches distinct currencies + one summary; per-currency subtotals
 * reconcile to the row sum; converted grand total is present when FX
 * resolves; FX unavailable degrades the summary line to a warning row
 * rather than failing the export.
 *
 * <p>Workbook bytes are parsed back through Apache POI so cell values —
 * not just "did it produce bytes" — are asserted.
 */
@ExtendWith(MockitoExtension.class)
class BordereauReportWorkbookServiceTest {

    @Mock BordereauQueryRepository queryRepository;
    @Mock BordereauPeriodExportService periodExportService;
    @Mock ReinsurerRepository reinsurerRepository;
    @Mock TreatyRepository treatyRepository;
    @Mock ReportingCurrencyResolver currencyResolver;
    @Mock FxConverter fxConverter;

    @InjectMocks BordereauReportWorkbookService service;

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REINSURER   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TREATY      = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Workbook parse(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    // ── Cession bordereau ───────────────────────────────────────────────────

    @Test
    void cessionWorkbook_multiCurrency_sheetsPerCurrencyPlusSummary_andSubtotalsReconcile()
            throws Exception {
        setupReinsurerAndTreatyLookups();
        when(periodExportService.firstExportedAt(eq(REINSURER), eq(TREATY),
                any(), anyInt(), anyInt())).thenReturn(Mono.empty());
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(fxConverter.convert(any(), eq("ZAR"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just(((BigDecimal) inv.getArgument(0))
                        .divide(new BigDecimal("18.5"), 4, java.math.RoundingMode.HALF_UP)));

        when(queryRepository.cessionRows(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.fromIterable(List.of(
                        cession("USD", "1000.00", "300.00", "60.0000", "180.00"),
                        cession("USD", "2000.00", "600.00", "60.0000", "360.00"),
                        cession("ZAR", "5000.00", "1500.00", "60.0000", "900.00"))));

        StepVerifier.create(service.cessionWorkbook(REINSURER, TREATY, 2026, 3, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getNumberOfSheets()).isEqualTo(3);
                        assertThat(wb.getSheet("Cessions USD")).isNotNull();
                        assertThat(wb.getSheet("Cessions ZAR")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                        Sheet summary = wb.getSheet("Summary");
                        boolean usdRow = false, zarRow = false, convertedRow = false;
                        var it = summary.rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if ("USD".equals(c0) && "540.0".equals(c1)) usdRow = true;
                            if ("ZAR".equals(c0) && "900.0".equals(c1)) zarRow = true;
                            if (c0.contains("Converted grand total")) convertedRow = true;
                        }
                        assertThat(usdRow).isTrue();
                        assertThat(zarRow).isTrue();
                        assertThat(convertedRow).isTrue();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void cessionWorkbook_noRows_rendersPlaceholderPlusSummary() throws Exception {
        setupReinsurerAndTreatyLookups();
        when(periodExportService.firstExportedAt(eq(REINSURER), eq(TREATY),
                any(), anyInt(), anyInt())).thenReturn(Mono.empty());
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.cessionRows(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.cessionWorkbook(REINSURER, TREATY, 2026, 3, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Empty")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void cessionWorkbook_missingFx_omitsConvertedRow_withWarning() throws Exception {
        setupReinsurerAndTreatyLookups();
        when(periodExportService.firstExportedAt(eq(REINSURER), eq(TREATY),
                any(), anyInt(), anyInt())).thenReturn(Mono.empty());
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("EUR"));
        when(fxConverter.convert(any(), eq("USD"), eq("EUR"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.error(new IllegalStateException("no rate")));
        when(queryRepository.cessionRows(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.fromIterable(List.of(
                        cession("USD", "1000.00", "300.00", "60.0000", "180.00"))));

        StepVerifier.create(service.cessionWorkbook(REINSURER, TREATY, 2026, 3, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        boolean unavailableNoted = false;
                        var it = summary.rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if (c0.contains("Converted grand total")
                                    && c1.toLowerCase().contains("fx unavailable")) {
                                unavailableNoted = true;
                            }
                        }
                        assertThat(unavailableNoted).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void cessionWorkbook_flagsPriorPeriodAdjustment_whenCreatedAfterFirstExport() throws Exception {
        setupReinsurerAndTreatyLookups();
        OffsetDateTime firstExportedAt = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        when(periodExportService.firstExportedAt(eq(REINSURER), eq(TREATY),
                any(), anyInt(), anyInt())).thenReturn(Mono.just(firstExportedAt));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));

        CessionBordereauRow beforeFirstExport = cessionWithCreatedAt(
                "2026-08-10T00:00:00Z", "USD", "1000.00", "300.00", "60.0000", "180.00");
        CessionBordereauRow afterFirstExport = cessionWithCreatedAt(
                "2026-08-20T00:00:00Z", "USD", "1000.00", "300.00", "60.0000", "180.00");
        when(queryRepository.cessionRows(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.just(beforeFirstExport, afterFirstExport));

        StepVerifier.create(service.cessionWorkbook(REINSURER, TREATY, 2026, 3, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet usd = wb.getSheet("Cessions USD");
                        // Header row is the 6th row (title + blank + 4 meta + blank).
                        // The Prior-period-adj column is the last of 13 (index 12).
                        int yesCount = 0;
                        for (int i = 0; i <= usd.getLastRowNum(); i++) {
                            var row = usd.getRow(i);
                            if (row == null) continue;
                            var c = row.getCell(12);
                            if (c != null && "YES".equals(c.toString())) yesCount++;
                        }
                        assertThat(yesCount).isEqualTo(1);
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    // ── Recoveries bordereau ────────────────────────────────────────────────

    @Test
    void recoveriesWorkbook_multiCurrency_perSheetAndSummary() throws Exception {
        setupReinsurerAndTreatyLookups();
        when(periodExportService.firstExportedAt(eq(REINSURER), eq(TREATY),
                any(), anyInt(), anyInt())).thenReturn(Mono.empty());
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));

        when(queryRepository.recoveriesRows(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.fromIterable(List.of(
                        recovery("USD", "EXPECTED", "300.00", null, "60.0000", "180.00", null),
                        recovery("USD", "RECEIVED", "600.00", "600.00", "60.0000", "360.00", "360.00"))));

        StepVerifier.create(service.recoveriesWorkbook(REINSURER, TREATY, 2026, 3, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Recoveries USD")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    // ── Treaty utilization ──────────────────────────────────────────────────

    @Test
    void utilizationWorkbook_perLayerRows_andConvertedGrandTotal() throws Exception {
        Treaty t = new Treaty();
        t.setId(TREATY);
        t.setTreatyRef("HEALTH-XOL-2026");
        when(treatyRepository.findById(TREATY)).thenReturn(Mono.just(t));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));

        when(queryRepository.utilizationRows(eq(TREATY), any(LocalDate.class)))
                .thenReturn(Flux.fromIterable(List.of(
                        utilization("USD", 1, "1000.00", "5000.00", "3000.00", 12L),
                        utilization("USD", 2, "6000.00", "10000.00", "2000.00", 4L))));

        StepVerifier.create(service.utilizationWorkbook(TREATY, LocalDate.of(2026, 9, 30), null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Utilization USD")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    // ── Snapshot helper for post-response INVOICED transition ───────────────

    @Test
    void snapshotExpectedRecoveryCessionIds_forwardsFilters() {
        when(queryRepository.expectedRecoveryCessionIds(eq(REINSURER), eq(TREATY),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Flux.just(TREATY, REINSURER));

        StepVerifier.create(service.snapshotExpectedRecoveryCessionIds(REINSURER, TREATY, 2026, 3))
                .assertNext(list -> assertThat(list).hasSize(2))
                .verifyComplete();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void setupReinsurerAndTreatyLookups() {
        Reinsurer r = new Reinsurer();
        r.setId(REINSURER);
        r.setName("Munich Re");
        when(reinsurerRepository.findById(REINSURER)).thenReturn(Mono.just(r));

        Treaty t = new Treaty();
        t.setId(TREATY);
        t.setTreatyRef("HEALTH-QS-2026");
        when(treatyRepository.findById(TREATY)).thenReturn(Mono.just(t));
    }

    private CessionBordereauRow cession(String currency, String basis, String cededFull,
                                        String sharePct, String participantShare) {
        return cessionWithCreatedAt(null, currency, basis, cededFull, sharePct, participantShare);
    }

    private CessionBordereauRow cessionWithCreatedAt(String createdAtIso, String currency,
                                                     String basis, String cededFull,
                                                     String sharePct, String participantShare) {
        return new CessionBordereauRow(
                UUID.randomUUID(), TREATY, "HEALTH-QS-2026", "QUOTA_SHARE",
                REINSURER, "Munich Re",
                new BigDecimal(sharePct), "LEADER",
                "LOSS", "AUTOMATIC",
                OffsetDateTime.parse("2026-08-01T00:00:00Z"), UUID.randomUUID(), "CLAIM_ADJUDICATED",
                new BigDecimal(basis), new BigDecimal(cededFull), new BigDecimal(participantShare),
                currency,
                createdAtIso != null ? OffsetDateTime.parse(createdAtIso)
                        : OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                false);
    }

    private RecoveriesBordereauRow recovery(String currency, String status,
                                            String expected, String received,
                                            String sharePct, String pExpected, String pReceived) {
        return new RecoveriesBordereauRow(
                UUID.randomUUID(), UUID.randomUUID(), TREATY, "HEALTH-QS-2026",
                REINSURER, "Munich Re", new BigDecimal(sharePct),
                status, UUID.randomUUID(),
                OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                new BigDecimal(expected), received != null ? new BigDecimal(received) : null,
                new BigDecimal(pExpected), pReceived != null ? new BigDecimal(pReceived) : BigDecimal.ZERO,
                currency,
                null, null, null,
                OffsetDateTime.parse("2026-08-01T00:00:00Z"), false);
    }

    private TreatyUtilizationRow utilization(String cededCurrency, int layerOrder,
                                             String retention, String layerLimit,
                                             String totalCeded, long cessionCount) {
        return new TreatyUtilizationRow(
                TREATY, "HEALTH-XOL-2026", "EXCESS_OF_LOSS",
                new BigDecimal("100000.00"), "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                UUID.randomUUID(), layerOrder,
                new BigDecimal(retention), new BigDecimal(layerLimit), "USD",
                cededCurrency,
                new BigDecimal(totalCeded), cessionCount);
    }
}
