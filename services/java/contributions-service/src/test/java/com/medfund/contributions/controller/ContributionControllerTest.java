package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.ChargePreviewLine;
import com.medfund.contributions.dto.ChargePreviewResponse;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.service.BillingService;
import com.medfund.shared.scheduler.JobDispatcher;
import com.medfund.shared.scheduler.ScheduledJobService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(ContributionController.class)
@Import(SecurityConfig.class)
class ContributionControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private BillingService billingService;

    @MockBean
    private ScheduledJobService scheduledJobService;

    @MockBean
    private JobDispatcher jobDispatcher;

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

    // ------------------------------------------------------------------
    // GET /api/v1/contributions/charge-preview
    // ------------------------------------------------------------------

    @Test
    void chargePreview_passesAllParamsToService_andReturns200() {
        UUID groupId = UUID.randomUUID();
        when(billingService.chargePreview(any(), any(), any()))
                .thenReturn(Mono.just(sampleResponse(groupId)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/contributions/charge-preview")
                        .queryParam("subjectType", "GROUP")
                        .queryParam("subjectId", groupId.toString())
                        .queryParam("currency", "USD")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.subjectType").isEqualTo("GROUP")
                .jsonPath("$.subjectName").isEqualTo("Acme Ltd")
                .jsonPath("$.lines[0].isCustomPriced").isEqualTo(true);

        verify(billingService).chargePreview("GROUP", groupId, "USD");
    }

    @Test
    void chargePreview_omitsOptionalCurrency_whenNotProvided() {
        // The "all currencies" flavour of the query — currency omitted,
        // service must receive a null. Regression here would silently
        // default to something like "USD" and hide the multi-currency
        // case from the operator.
        UUID memberId = UUID.randomUUID();
        when(billingService.chargePreview(any(), any(), any()))
                .thenReturn(Mono.just(sampleResponse(memberId)));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/contributions/charge-preview")
                        .queryParam("subjectType", "MEMBER")
                        .queryParam("subjectId", memberId.toString())
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();

        verify(billingService).chargePreview(eq("MEMBER"), eq(memberId), isNull());
    }

    @Test
    void chargePreview_missingSubjectId_returns400() {
        // Guard against silent 200-with-empty-body responses. A caller
        // that omits subjectId should fail loudly at the boundary.
        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/contributions/charge-preview")
                        .queryParam("subjectType", "GROUP")
                        .build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private ChargePreviewResponse sampleResponse(UUID subjectId) {
        LocalDate periodStart = LocalDate.of(2026, 8, 1);
        LocalDate periodEnd   = LocalDate.of(2026, 8, 31);
        ChargePreviewLine line = new ChargePreviewLine(
                UUID.randomUUID(), null, "M-100", "Jane Doe", "MEMBER",
                UUID.randomUUID(), "Gold Plan",
                subjectId, "Acme Ltd",
                UUID.randomUUID(), "Adult",
                new BigDecimal("125"), "USD",
                true, null);
        return new ChargePreviewResponse(
                "GROUP", subjectId, "Acme Ltd",
                periodStart, periodEnd,
                List.of(line),
                Map.of("USD", new BigDecimal("125")),
                0, Instant.now());
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
