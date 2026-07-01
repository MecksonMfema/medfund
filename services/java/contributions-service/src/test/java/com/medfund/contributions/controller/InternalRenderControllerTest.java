package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.InvoiceContributionRow;
import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.exception.GlobalExceptionHandler;
import com.medfund.contributions.exception.InvoiceNotFoundException;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.service.InvoiceListService;
import com.medfund.contributions.service.StatementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Locks down the JWT-exempt render endpoint that file-service depends on.
 * Three regressions this catches:
 *
 * <ol>
 *   <li>{@code /api/v1/internal/**} slipping out of the permitAll list in
 *       {@link SecurityConfig} — file-service has no user session, so any
 *       401 there breaks every subsequent PDF.</li>
 *   <li>The combined payload shape drifting from what
 *       {@code contributions.Client} expects on the file-service side.</li>
 *   <li>The tenant guard being loosened — TenantWebFilter must still
 *       reject requests without X-Tenant-ID even on internal paths.</li>
 * </ol>
 */
@WebFluxTest(InternalRenderController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InternalRenderControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private InvoiceRepository invoiceRepository;
    @MockBean
    private InvoiceListService invoiceListService;
    @MockBean
    private StatementService statementService;

    private static final String TENANT_UUID = "68b43674-68d5-48d3-9d89-1aae22da743c";

    @Test
    void renderPayload_returnsCombinedInvoiceStatementContributions_withoutJwt() {
        // The invoice-id here is the one from the reported bad-PDF (INV-008873)
        // so a failure explicitly references the real regression scenario.
        UUID id = UUID.fromString("582e546c-6786-4a80-8f2f-346b58dd40dc");

        when(invoiceRepository.findById(id)).thenReturn(Mono.just(sampleInvoice(id)));
        when(statementService.generateForInvoice(id)).thenReturn(Mono.just(sampleStatement()));
        when(invoiceListService.contributionsFor(id))
                .thenReturn(Flux.just(sampleContribution()));

        webTestClient
                // No mockJwt() — the endpoint must be reachable purely via
                // permitAll("/api/v1/internal/**"). If SecurityConfig regresses
                // this call flips to 401.
                .get().uri("/api/v1/internal/invoices/{id}/render-payload", id)
                .header("X-Tenant-ID", TENANT_UUID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // All three components must be present and populated.
                // Renderer regressions where one field drops off (e.g.
                // schema DTO rename) fail here.
                .jsonPath("$.invoice.invoiceNumber").isEqualTo("INV-008873")
                .jsonPath("$.invoice.currencyCode").isEqualTo("USD")
                .jsonPath("$.statement.header.targetName").isEqualTo("Test group")
                .jsonPath("$.statement.header.openingBalance").isEqualTo(0)
                .jsonPath("$.statement.header.closingBalance").isEqualTo(115.00)
                .jsonPath("$.contributions[0].memberName").isEqualTo("Methuseli Mfema")
                .jsonPath("$.contributions[0].personType").isEqualTo("MEMBER");
    }

    @Test
    void renderPayload_withoutTenantHeader_returns400() {
        // TenantWebFilter (from shared) is picked up by @WebFluxTest as a
        // WebFilter component and rejects tenant-scoped paths without the
        // header. This test guarantees the internal path stays tenant-scoped
        // — a regression that exempts it would leak data across tenants.
        webTestClient
                .get().uri("/api/v1/internal/invoices/{id}/render-payload", UUID.randomUUID())
                // no X-Tenant-ID header
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void renderPayload_notFoundInvoice_propagates() {
        UUID id = UUID.randomUUID();
        when(invoiceRepository.findById(id)).thenReturn(Mono.empty());
        // The controller wraps empty into InvoiceNotFoundException — the
        // combined Mono.zip must not silently succeed with a partial
        // payload.
        when(statementService.generateForInvoice(id))
                .thenReturn(Mono.error(new InvoiceNotFoundException(id)));
        when(invoiceListService.contributionsFor(id)).thenReturn(Flux.empty());

        webTestClient
                .get().uri("/api/v1/internal/invoices/{id}/render-payload", id)
                .header("X-Tenant-ID", TENANT_UUID)
                .exchange()
                // GlobalExceptionHandler maps InvoiceNotFoundException → 404
                // via ProblemDetail. If the controller ever silently zipWith's
                // Mono.empty() into a partial success, this flips to 200.
                .expectStatus().isNotFound();
    }

    // ─── Fixtures ─────────────────────────────────────────────────

    private Invoice sampleInvoice(UUID id) {
        var inv = new Invoice();
        inv.setId(id);
        inv.setInvoiceNumber("INV-008873");
        inv.setGroupId(UUID.fromString("f235b452-50d1-43e1-930c-0e93be3279fa"));
        inv.setTotalAmount(new BigDecimal("115.00"));
        inv.setCurrencyCode("USD");
        inv.setStatus("issued");
        inv.setPeriodStart(LocalDate.of(2026, 8, 1));
        inv.setPeriodEnd(LocalDate.of(2026, 8, 31));
        inv.setDueDate(LocalDate.of(2026, 9, 30));
        inv.setIssuedAt(Instant.parse("2026-07-01T19:45:10Z"));
        inv.setCommittedAt(Instant.parse("2026-07-01T19:45:10Z"));
        inv.setOpeningBalance(new BigDecimal("0.00"));
        inv.setClosingBalance(new BigDecimal("115.00"));
        inv.setCreatedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        return inv;
    }

    private StatementResponse sampleStatement() {
        var header = new StatementResponse.Header(
                "GROUP",
                UUID.fromString("f235b452-50d1-43e1-930c-0e93be3279fa"),
                "Test group",
                "12345",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "USD",
                new BigDecimal("0.00"),
                new BigDecimal("115.00"),
                new BigDecimal("115.00"),
                new BigDecimal("0.00"));
        return new StatementResponse(header, List.of());
    }

    private InvoiceContributionRow sampleContribution() {
        return new InvoiceContributionRow(
                UUID.randomUUID(),
                "MBR-856182",
                "Methuseli Mfema",
                "MEMBER",
                null,
                "Test Scheme Edited",
                "HEALTH",
                "Adult",
                new BigDecimal("65.00"),
                "USD");
    }
}
