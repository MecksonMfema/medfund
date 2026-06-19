package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.Adjustment;
import com.medfund.finance.service.AdjustmentService;
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

@WebFluxTest(AdjustmentController.class)
@Import(SecurityConfig.class)
class AdjustmentControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AdjustmentService adjustmentService;

    @Test
    void findByProviderId_returns200() {
        UUID providerId = UUID.randomUUID();
        when(adjustmentService.findByProviderId(providerId)).thenReturn(Flux.just(createTestAdjustment()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/adjustments/provider/{providerId}", providerId)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(adjustmentService.findById(id)).thenReturn(Mono.just(createTestAdjustment()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/adjustments/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(adjustmentService.create(any(), any(), any())).thenReturn(Mono.just(createTestAdjustment()));

        String body = """
                {
                    "providerId": "%s",
                    "adjustmentType": "credit",
                    "amount": 500.00,
                    "currencyCode": "USD",
                    "reason": "Overpayment correction"
                }
                """.formatted(UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Adjustment createTestAdjustment() {
        var a = new Adjustment();
        a.setId(UUID.randomUUID());
        a.setAdjustmentNumber("ADJ-000001");
        a.setProviderId(UUID.randomUUID());
        a.setAdjustmentType("credit");
        a.setAmount(new BigDecimal("500.00"));
        a.setCurrencyCode("USD");
        a.setReason("Overpayment correction");
        a.setStatus("pending");
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }
}
