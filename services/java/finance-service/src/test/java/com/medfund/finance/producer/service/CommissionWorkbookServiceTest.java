package com.medfund.finance.producer.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.CommissionReportQueryRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the multi-sheet commission XLSX shape end-to-end: sheet count
 * matches distinct grouping key + one Summary; per-currency subtotals
 * reconcile to the row sum; converted grand total is present when FX
 * resolves; missing FX degrades the summary line to a warning rather than
 * failing the export.
 *
 * <p>Workbook bytes are parsed back through Apache POI so cell values —
 * not just "did it produce bytes" — are asserted. Mirrors
 * {@code BordereauReportWorkbookServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class CommissionWorkbookServiceTest {

    @Mock CommissionReportQueryRepository queryRepository;
    @Mock ProducerRepository producerRepository;
    @Mock ReportingCurrencyResolver currencyResolver;
    @Mock FxConverter fxConverter;

    @InjectMocks CommissionWorkbookService service;

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCER_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRODUCER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 9, 30);

    private Workbook parse(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    // ── Commission statement ────────────────────────────────────────────────

    @Test
    void statementWorkbook_multiCurrency_sheetPerCurrencyPlusSummary_andSubtotalsReconcile()
            throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(fxConverter.convert(any(), eq("ZAR"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just(((BigDecimal) inv.getArgument(0))
                        .divide(new BigDecimal("18.5"), 4, java.math.RoundingMode.HALF_UP)));

        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        row("COMM-2026-000001", PRODUCER_A, "USD", "50.0000"),
                        row("COMM-2026-000002", PRODUCER_A, "USD", "75.0000"),
                        row("COMM-2026-000003", PRODUCER_B, "ZAR", "900.0000"))));

        StepVerifier.create(service.statementWorkbook(PERIOD_START, PERIOD_END,
                        null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getNumberOfSheets()).isEqualTo(3);
                        assertThat(wb.getSheet("Statement USD")).isNotNull();
                        assertThat(wb.getSheet("Statement ZAR")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                        Sheet summary = wb.getSheet("Summary");
                        boolean usdRow = false, zarRow = false, convertedRow = false;
                        var it = summary.rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if ("USD".equals(c0) && c1.startsWith("125.")) usdRow = true;
                            if ("ZAR".equals(c0) && c1.startsWith("900.")) zarRow = true;
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
    void statementWorkbook_noRows_rendersPlaceholderPlusSummary() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.statementRows(any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.statementWorkbook(PERIOD_START, PERIOD_END,
                        null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Empty")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void statementWorkbook_missingFx_omitsConvertedTotal_withWarning() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("EUR"));
        when(fxConverter.convert(any(), eq("USD"), eq("EUR"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.error(new IllegalStateException("no rate")));
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        row("COMM-2026-000001", PRODUCER_A, "USD", "50.0000"))));

        StepVerifier.create(service.statementWorkbook(PERIOD_START, PERIOD_END,
                        null, "EUR", TENANT_ID))
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
    void statementWorkbook_producerFilter_labelsProducerInMeta() throws Exception {
        Producer p = new Producer();
        p.setId(PRODUCER_A);
        p.setName("Test Broker");
        p.setProducerCode("BRK-001");
        when(producerRepository.findById(PRODUCER_A)).thenReturn(Mono.just(p));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(queryRepository.statementRows(any(), any(), eq(PRODUCER_A)))
                .thenReturn(Flux.fromIterable(List.of(
                        row("COMM-2026-000001", PRODUCER_A, "USD", "50.0000"))));

        StepVerifier.create(service.statementWorkbook(PERIOD_START, PERIOD_END,
                        PRODUCER_A, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        boolean producerMeta = false;
                        for (int i = 0; i <= summary.getLastRowNum(); i++) {
                            var r = summary.getRow(i);
                            if (r == null) continue;
                            var c0 = r.getCell(0);
                            var c1 = r.getCell(1);
                            if (c0 != null && "Producer".equals(c0.toString())
                                    && c1 != null && c1.toString().contains("Test Broker")) {
                                producerMeta = true;
                            }
                        }
                        assertThat(producerMeta).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void statementWorkbook_escapesSpecialCharactersInProducerName() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        String awkward = "A&B \"quoted\" <name>";
        when(queryRepository.statementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        rowWithProducerName("COMM-2026-000001", PRODUCER_A, "USD", "50.0000", awkward))));

        StepVerifier.create(service.statementWorkbook(PERIOD_START, PERIOD_END,
                        null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet usd = wb.getSheet("Statement USD");
                        boolean found = false;
                        for (int i = 0; i <= usd.getLastRowNum(); i++) {
                            var r = usd.getRow(i);
                            if (r == null) continue;
                            for (int j = 0; j < 14; j++) {
                                var c = r.getCell(j);
                                if (c != null && awkward.equals(c.toString())) found = true;
                            }
                        }
                        assertThat(found).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    // ── Clawback register ───────────────────────────────────────────────────

    @Test
    void clawbackWorkbook_sheetPerSourcePlusSummary() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxConverter.convert(any(), eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(queryRepository.clawbackRows(any(), any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        clawback("MEMBER_LAPSE", "COMM-2026-000010", "USD", "50.0000"),
                        clawback("CONTRIBUTION_REVOKE", "COMM-2026-000011", "USD", "30.0000"))));

        StepVerifier.create(service.clawbackWorkbook(PERIOD_START, PERIOD_END,
                        null, null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Clawback MEMBER_LAPSE")).isNotNull();
                        assertThat(wb.getSheet("Clawback CONTRIBUTION_REVOKE")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void clawbackWorkbook_noRows_rendersPlaceholderPlusSummary() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(queryRepository.clawbackRows(any(), any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.clawbackWorkbook(PERIOD_START, PERIOD_END,
                        null, null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Empty")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CommissionStatementRow row(String reference, UUID producerId,
                                       String currency, String nativeAmount) {
        return rowWithProducerName(reference, producerId, currency, nativeAmount, "Broker " + producerId);
    }

    private CommissionStatementRow rowWithProducerName(String reference, UUID producerId,
                                                       String currency, String nativeAmount,
                                                       String producerName) {
        return new CommissionStatementRow(
                UUID.randomUUID(), reference, producerId, "BRK-001", producerName, "USD",
                UUID.randomUUID(), UUID.randomUUID(), "HEALTH",
                UUID.randomUUID(), "Standard 10%",
                new BigDecimal("10.0000"),
                new BigDecimal("500.00"),
                new BigDecimal(nativeAmount), currency,
                "ACCRUED",
                OffsetDateTime.of(2026, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC));
    }

    private ClawbackRegisterRow clawback(String source, String commissionRef,
                                         String currency, String nativeAmount) {
        return new ClawbackRegisterRow(
                UUID.randomUUID(), source, UUID.randomUUID().toString(),
                UUID.randomUUID(), PRODUCER_A, "BRK-001", "Test Broker",
                UUID.randomUUID(), commissionRef,
                new BigDecimal(nativeAmount), currency,
                "auto-triggered",
                OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC));
    }
}
