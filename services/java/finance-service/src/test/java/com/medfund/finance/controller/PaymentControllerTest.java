package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.service.PaymentService;
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

@WebFluxTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private PaymentService paymentService;

    @Test
    void findAll_returns200() {
        when(paymentService.findAll()).thenReturn(Flux.just(createTestPayment()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/payments")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(paymentService.findById(id)).thenReturn(Mono.just(createTestPayment()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/payments/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(paymentService.create(any(), any(), any())).thenReturn(Mono.just(createTestPayment()));

        String body = """
                {
                    "providerId": "%s",
                    "amount": 1000.00,
                    "currencyCode": "USD",
                    "paymentType": "claim_payment",
                    "paymentMethod": "bank_transfer"
                }
                """.formatted(UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Payment createTestPayment() {
        var p = new Payment();
        p.setId(UUID.randomUUID());
        p.setPaymentNumber("PAY-000001");
        p.setProviderId(UUID.randomUUID());
        p.setAmount(new BigDecimal("1000.00"));
        p.setCurrencyCode("USD");
        p.setStatus("pending");
        p.setPaymentType("claim_payment");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }
}
