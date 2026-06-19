package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.service.BillingService;
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

@WebFluxTest(ContributionController.class)
@Import(SecurityConfig.class)
class ContributionControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private BillingService billingService;

    @Test
    void findByMemberId_returns200() {
        UUID memberId = UUID.randomUUID();
        when(billingService.findContributionsByMemberId(memberId)).thenReturn(Flux.just(createTestContribution()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/contributions/member/{memberId}", memberId)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(billingService.findContributionById(id)).thenReturn(Mono.just(createTestContribution()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/contributions/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void generateBilling_returns201() {
        when(billingService.generateBilling(any(), any(), any())).thenReturn(Mono.just(1L));

        String body = """
                {
                    "schemeId": "%s",
                    "groupId": "%s",
                    "periodStart": "2026-01-01",
                    "periodEnd": "2026-01-31"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/contributions/generate-billing")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Contribution createTestContribution() {
        var c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setAmount(new BigDecimal("250.00"));
        c.setCurrencyCode("USD");
        c.setStatus("pending");
        c.setPeriodStart(LocalDate.of(2026, 1, 1));
        c.setPeriodEnd(LocalDate.of(2026, 1, 31));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }
}
