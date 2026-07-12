package com.medfund.claims.controller;

import com.medfund.claims.config.SecurityConfig;
import com.medfund.claims.dto.ClaimResponse;
import com.medfund.claims.dto.ClaimSubmissionResponse;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.service.ClaimService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(ClaimController.class)
@Import(SecurityConfig.class)
class ClaimControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ClaimService claimService;

    @MockBean
    private ClaimLineRepository claimLineRepository;

    @Test
    void findAll_returns200() {
        when(claimService.findAll()).thenReturn(Flux.just(createTestClaim()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/claims")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(claimService.findById(id)).thenReturn(Mono.just(createTestClaim()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/claims/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_nonExisting_returns404() {
        UUID id = UUID.randomUUID();
        when(claimService.findById(id)).thenReturn(Mono.error(new ClaimNotFoundException(id)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/claims/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void submit_returns201WithSubmissionEnvelope() {
        // The controller returns an envelope: the created claim plus the
        // (nullable) batch number in a single payload. Pins the shape so
        // a "flatten it back to ClaimResponse" refactor fails loudly.
        var envelope = new ClaimSubmissionResponse(
                ClaimResponse.from(createTestClaim()),
                "BATCH999"
        );
        when(claimService.submit(any(), any(), any())).thenReturn(Mono.just(envelope));

        String body = """
                {
                    "memberId": "%s",
                    "providerId": "%s",
                    "schemeId": "%s",
                    "serviceDate": "2026-01-15",
                    "claimedAmount": 500.00,
                    "currencyCode": "USD",
                    "lines": [{"tariffCode":"TC001","quantity":1,"unitPrice":"500.00","claimedAmount":"500.00"}]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/claims")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.batchNumber").isEqualTo("BATCH999")
                .jsonPath("$.claim.claimNumber").isEqualTo("CLM-123456");
    }

    private Claim createTestClaim() {
        var c = new Claim();
        c.setId(UUID.randomUUID());
        c.setClaimNumber("CLM-123456");
        c.setStatus("SUBMITTED");
        c.setClaimedAmount(new BigDecimal("500.00"));
        c.setCurrencyCode("USD");
        c.setServiceDate(LocalDate.now());
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }
}
