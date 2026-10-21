package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.service.BillingService;
import com.medfund.contributions.service.InvoiceListService;
import com.medfund.contributions.service.StatementService;
import com.medfund.shared.security.SecurityEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
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
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(InvoiceController.class)
@Import({SecurityConfig.class, InvoiceControllerTest.WebClientBuilderCfg.class})
class InvoiceControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class WebClientBuilderCfg {
        // Real (unused) builder — the controller instantiates a WebClient
        // in its ctor via builder.baseUrl(...).build(). Tests never call
        // the PDF proxy endpoint, so the underlying WebClient can safely
        // point at localhost.
        @org.springframework.context.annotation.Bean
        WebClient.Builder webClientBuilder() { return WebClient.builder(); }
    }

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private BillingService billingService;

    @MockBean
    private InvoiceRepository invoiceRepository;

    @MockBean
    private InvoiceListService invoiceListService;

    @MockBean
    private StatementService statementService;

    @MockBean
    private SecurityEventPublisher securityEventPublisher;


    @Test
    void findByGroupId_returns200() {
        UUID groupId = UUID.randomUUID();
        when(billingService.findInvoicesByGroupId(groupId)).thenReturn(Flux.just(createTestInvoice()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/invoices/group/{groupId}", groupId)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(billingService.findInvoiceById(id)).thenReturn(Mono.just(createTestInvoice()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/invoices/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    private Invoice createTestInvoice() {
        var inv = new Invoice();
        inv.setId(UUID.randomUUID());
        inv.setInvoiceNumber("INV-000001");
        inv.setTotalAmount(new BigDecimal("1500.00"));
        inv.setCurrencyCode("USD");
        inv.setStatus("draft");
        inv.setPeriodStart(LocalDate.of(2026, 1, 1));
        inv.setPeriodEnd(LocalDate.of(2026, 1, 31));
        inv.setCreatedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        return inv;
    }
}
