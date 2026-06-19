package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.service.PaymentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(PaymentRunController.class)
@Import(SecurityConfig.class)
class PaymentRunControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private PaymentRunService paymentRunService;

    @Test
    void findAll_returns200() {
        when(paymentRunService.findAll()).thenReturn(Flux.just(createTestPaymentRun()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/payment-runs")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(paymentRunService.findById(id)).thenReturn(Mono.just(createTestPaymentRun()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/payment-runs/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(paymentRunService.create(any(), any(), any())).thenReturn(Mono.just(createTestPaymentRun()));

        String body = """
                {
                    "description": "January 2026 provider payments",
                    "currencyCode": "USD"
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/payment-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private PaymentRun createTestPaymentRun() {
        var pr = new PaymentRun();
        pr.setId(UUID.randomUUID());
        pr.setRunNumber("RUN-000001");
        pr.setStatus("draft");
        pr.setTotalAmount(new BigDecimal("50000.00"));
        pr.setCurrencyCode("USD");
        pr.setPaymentCount(10);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        return pr;
    }
}
