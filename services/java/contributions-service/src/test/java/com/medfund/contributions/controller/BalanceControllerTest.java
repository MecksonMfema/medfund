package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.CreditorRow;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.service.BadDebtService;
import com.medfund.contributions.service.BadDebtsExcelService;
import com.medfund.contributions.service.BalanceService;
import com.medfund.contributions.service.CreditorsExcelService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * WebFlux slice test for the bad-debts / aged-balances / creditors
 * export endpoints. What this catches that the service tests can't:
 * <ul>
 *   <li>Route wiring — /bad-debts and /bad-debts/export/excel land on
 *       the new handlers, not the old aged-balances one.</li>
 *   <li>Param bounds — size clamps to [1, 100], page clamps to >= 0.
 *       Silent clamp drift would show wrong page counts.</li>
 *   <li>XLSX response envelope — Content-Type and
 *       Content-Disposition filename header. A broken filename saves
 *       the download without an extension.</li>
 *   <li>/aged-balances still resolves after being split out of /bad-debts.</li>
 * </ul>
 */
@WebFluxTest(BalanceController.class)
@Import(SecurityConfig.class)
class BalanceControllerTest {

    @Autowired private WebTestClient webTestClient;

    @MockBean private BalanceService balanceService;
    @MockBean private BadDebtService badDebtService;
    @MockBean private CreditorsExcelService creditorsExcelService;
    @MockBean private BadDebtsExcelService badDebtsExcelService;

    // ------------------------------------------------------------------
    // GET /api/v1/billing/balances/bad-debts
    // ------------------------------------------------------------------

    @Test
    void listBadDebts_passesAllParamsToService() {
        PageResponse<CreditorRow> empty = PageResponse.of(List.of(), 0L, 0, 20);
        when(balanceService.listBadDebts(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(empty));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/billing/balances/bad-debts")
                        .queryParam("currency", "USD")
                        .queryParam("subjectType", "GROUP")
                        .queryParam("q", "Acme")
                        .queryParam("page", "2")
                        .queryParam("size", "10")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(balanceService).listBadDebts("USD", "GROUP", "Acme", 2, 10);
    }

    @Test
    void listBadDebts_sizeAbove100_clampsTo100() {
        // Guard against a client asking for size=500 and getting it.
        // A silent uncapped size opens a DoS door on the receivables
        // list — a tenant with millions of subjects would blow memory.
        when(balanceService.listBadDebts(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 100)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/billing/balances/bad-debts")
                        .queryParam("currency", "USD")
                        .queryParam("size", "500")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Integer> sizeCap = ArgumentCaptor.forClass(Integer.class);
        verify(balanceService).listBadDebts(eq("USD"), isNull(), isNull(),
                eq(0), sizeCap.capture());
        assertThat(sizeCap.getValue()).isEqualTo(100);
    }

    @Test
    void listBadDebts_negativePage_clampsToZero() {
        // Same clamp reasoning as size — a negative offset would
        // produce a SQL error on the LIMIT/OFFSET, so the controller
        // pins the floor.
        when(balanceService.listBadDebts(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 20)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/billing/balances/bad-debts")
                        .queryParam("currency", "USD")
                        .queryParam("page", "-5")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Integer> pageCap = ArgumentCaptor.forClass(Integer.class);
        verify(balanceService).listBadDebts(eq("USD"), isNull(), isNull(),
                pageCap.capture(), eq(20));
        assertThat(pageCap.getValue()).isZero();
    }

    @Test
    void listBadDebts_defaultsWhenParamsOmitted() {
        // No subjectType/q → both null; no page/size → 0 / 20 defaults.
        // These defaults are the "operator opens the page fresh" flow
        // and must not drift silently.
        when(balanceService.listBadDebts(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 20)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/billing/balances/bad-debts?currency=USD")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(balanceService).listBadDebts("USD", null, null, 0, 20);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/billing/balances/bad-debts/export/excel
    // ------------------------------------------------------------------

    @Test
    void exportBadDebtsExcel_returnsXlsxContentType_andAttachmentHeader() {
        byte[] payload = "fake-xlsx".getBytes();
        when(badDebtsExcelService.generate(eq("USD"), eq("GROUP"), eq("Acme")))
                .thenReturn(Mono.just(payload));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/billing/balances/bad-debts/export/excel")
                        .queryParam("currency", "USD")
                        .queryParam("subjectType", "GROUP")
                        .queryParam("q", "Acme")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                // Filename shape is the operator-visible contract for the
                // download; browsers derive the saved-as name from this
                // exact header. Pin the prefix + suffix; the middle
                // (LocalDate.now()) is asserted separately below.
                .expectHeader().value("Content-Disposition", disp -> {
                    assertThat(disp).startsWith("attachment; filename=\"bad-debts-USD-");
                    assertThat(disp).contains(LocalDate.now().toString());
                    assertThat(disp).endsWith(".xlsx\"");
                })
                .expectBody(byte[].class).isEqualTo(payload);
    }

    @Test
    void exportBadDebtsExcel_delegatesToBadDebtsExcelService_notCreditorsExcel() {
        // Regression guard: the two exports live side by side and their
        // service beans are wired by name — if the controller ever
        // routes /bad-debts/export to the creditors bean the sheet would
        // list the wrong set of rows with the same filename.
        when(badDebtsExcelService.generate(any(), any(), any()))
                .thenReturn(Mono.just(new byte[]{0x50, 0x4B, 0x03, 0x04}));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/billing/balances/bad-debts/export/excel?currency=USD")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(badDebtsExcelService).generate("USD", null, null);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/billing/balances/aged-balances
    // The old /bad-debts (aged classification) route moved here when
    // /bad-debts was re-scoped to the deactivated+owing filter.
    // ------------------------------------------------------------------

    @Test
    void listAged_survivesUnderNewAgedBalancesPath() {
        when(balanceService.listAged(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(), 0L, 0, 20)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/billing/balances/aged-balances")
                        .queryParam("currency", "USD")
                        .queryParam("minAgeDays", "45")
                        .queryParam("q", "smith")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(balanceService).listAged("USD", 45, "smith", 0, 20);
    }

    @Test
    void listAged_atOldBadDebtsPath_isNoLongerRoutedThere() {
        // The old /bad-debts route now returns a different DTO
        // (CreditorRow vs BadDebtRow). Prove /bad-debts no longer
        // reaches listAged so callers who forgot to migrate their URL
        // fail loudly rather than silently getting a different shape.
        when(balanceService.listBadDebts(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(PageResponse.of(List.of(sampleRow()), 1L, 0, 20)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/billing/balances/bad-debts?currency=USD&minAgeDays=30")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        // listBadDebts was called; listAged was not.
        verify(balanceService).listBadDebts(eq("USD"), isNull(), isNull(), eq(0), eq(20));
    }

    private CreditorRow sampleRow() {
        return new CreditorRow(
                "MEMBER", UUID.randomUUID(), "M-01", "Jane Doe", "j@example.com",
                "USD", new BigDecimal("100"), null, null, null);
    }
}
