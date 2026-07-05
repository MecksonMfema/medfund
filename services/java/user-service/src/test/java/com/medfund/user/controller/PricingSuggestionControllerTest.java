package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.dto.PricingSuggestionResponse;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.service.PricingSuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * HTTP-slice test for {@link PricingSuggestionController}. Covers the
 * two seams the pure-JUnit service tests can't reach:
 *
 * <ul>
 *   <li>The controller wires the {@code POST /api/v1/pricing-suggestions}
 *       route to the service — a rename or path-change here would
 *       silently break the frontend's "Suggest with AI" button.</li>
 *   <li>Bean validation on the request body — {@code @NotNull schemeId}
 *       and {@code @NotNull dateOfBirth} must reject with 400 before
 *       the service is called. Otherwise a stray null would surface
 *       as a 500 from a downstream NPE.</li>
 * </ul>
 */
@WebFluxTest(PricingSuggestionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PricingSuggestionControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    PricingSuggestionService pricingSuggestionService;

    @Test
    void suggest_validRequest_returns200_andForwardsToService() {
        var response = new PricingSuggestionResponse(
                new BigDecimal("175.50"),
                "USD",
                "Adult band × smoker",
                List.of("Adult 30-40", "Smoker ×1.30"),
                true
        );
        when(pricingSuggestionService.suggest(any())).thenReturn(Mono.just(response));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/pricing-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"schemeId\":\"" + UUID.randomUUID() + "\","
                        + "\"dateOfBirth\":\"1990-05-15\","
                        + "\"gender\":\"male\","
                        + "\"smoker\":true,"
                        + "\"hasChronicConditions\":false,"
                        + "\"bmi\":24.5}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.suggestedAmount").isEqualTo(175.50)
                .jsonPath("$.currencyCode").isEqualTo("USD")
                .jsonPath("$.stub").isEqualTo(true)
                // factors[] must survive JSON serialisation as an array —
                // regression here would leave the operator UI without the
                // rationale breakdown even on a successful call.
                .jsonPath("$.factors[0]").isEqualTo("Adult 30-40");

        verify(pricingSuggestionService).suggest(any());
    }

    @Test
    void suggest_missingSchemeId_returns400() {
        // @NotNull on schemeId — the guard has to fire in the controller
        // slice, not just fail with an NPE downstream. Without the
        // @Valid on the controller signature we'd get a 500 here.
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/pricing-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"dateOfBirth\":\"1990-05-15\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void suggest_missingDateOfBirth_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/pricing-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"schemeId\":\"" + UUID.randomUUID() + "\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void suggest_invalidGender_returns400() {
        // gender is optional but pattern-constrained. An arbitrary string
        // must reject — the resolver would treat unknown values as
        // "no multiplier" but validation should never let it get that far.
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/pricing-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"schemeId\":\"" + UUID.randomUUID() + "\","
                        + "\"dateOfBirth\":\"1990-05-15\","
                        + "\"gender\":\"unknown-value\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
