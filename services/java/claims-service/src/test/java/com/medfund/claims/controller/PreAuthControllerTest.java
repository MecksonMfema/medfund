package com.medfund.claims.controller;

import com.medfund.claims.config.SecurityConfig;
import com.medfund.claims.entity.PreAuthorization;
import com.medfund.claims.service.PreAuthService;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(PreAuthController.class)
@Import(SecurityConfig.class)
class PreAuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private PreAuthService preAuthService;

    @Test
    void findByStatus_returns200() {
        when(preAuthService.findByStatus("PENDING")).thenReturn(Flux.just(createTestPreAuth()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/pre-authorizations?status=PENDING")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(preAuthService.findById(id)).thenReturn(Mono.just(createTestPreAuth()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/pre-authorizations/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void request_returns201() {
        when(preAuthService.request(any(), any(), any())).thenReturn(Mono.just(createTestPreAuth()));

        String body = """
                {
                    "memberId": "%s",
                    "providerId": "%s",
                    "schemeId": "%s",
                    "tariffCode": "SURG-001",
                    "diagnosisCode": "K35.8",
                    "requestedAmount": 5000.00,
                    "currencyCode": "USD"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/pre-authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private PreAuthorization createTestPreAuth() {
        var pa = new PreAuthorization();
        pa.setId(UUID.randomUUID());
        pa.setAuthNumber("PA-000001");
        pa.setStatus("PENDING");
        pa.setRequestedAmount(new BigDecimal("5000.00"));
        pa.setCurrencyCode("USD");
        pa.setRequestedDate(LocalDate.now());
        pa.setCreatedAt(Instant.now());
        pa.setUpdatedAt(Instant.now());
        return pa;
    }
}
