package com.medfund.tenancy.integration;

import com.medfund.shared.tenant.TenantContext;
import com.medfund.shared.tenant.TenantWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * End-to-end test of the {@link TenantWebFilter} security boundary —
 * every tenant-scoped HTTP request MUST present a valid {@code X-Tenant-ID}
 * header or be rejected with a {@code ProblemDetail}-shaped 400.
 *
 * <p>Boots the reactive web stack with a probe endpoint that echoes the
 * tenant ID resolved from the Reactor context, so we can assert both the
 * rejection path and the happy path through one controller.
 *
 * <p>Why a slice integration test: filter ordering, exclusion path matching,
 * and the {@code Content-Type: application/problem+json} negotiation are
 * concerns that only show up once Spring boots the WebFilterChain end-to-end.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = {TenantWebFilterIT.ProbeConfig.class, TenantWebFilter.class}
)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
@Import(TenantWebFilterIT.ProbeConfig.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration," +
        "org.springframework.boot.autoconfigure.r2dbc.R2dbcRepositoriesAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration," +
        "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration"
})
class TenantWebFilterIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ProbeConfig {
        /**
         * Single GET endpoint that reads the tenant from the Reactor context
         * and echoes it as JSON. Lets us assert that the filter actually wrote
         * the tenant in — not just that the filter passed the request through.
         */
        @Bean
        RouterFunction<ServerResponse> probeRouter() {
            return RouterFunctions.route(GET("/api/v1/probe"),
                req -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"tenantId\":\"" + (tenantId == null ? "" : tenantId) + "\"}");
                })
            );
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private WebTestClient client;

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    @Test
    void missingTenantHeader_returns400WithProblemDetail() {
        client.get().uri("/api/v1/probe")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.title").isEqualTo("Tenant Required")
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.instance").isEqualTo("/api/v1/probe");
    }

    @Test
    void blankTenantHeader_returns400() {
        client.get().uri("/api/v1/probe")
            .header("X-Tenant-ID", "   ")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void validTenantHeader_passesAndIsReadableFromReactorContext() {
        var result = client.get().uri("/api/v1/probe")
            .header("X-Tenant-ID", TENANT_A)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult();
        assertThat(result.getResponseBody()).contains("\"tenantId\":\"" + TENANT_A + "\"");
    }

    @Test
    void crossTenantHeader_neverLeaksPreviousTenantIntoContext() {
        // Hit A then B back-to-back; the second response must reflect B alone.
        client.get().uri("/api/v1/probe").header("X-Tenant-ID", TENANT_A)
            .exchange().expectStatus().isOk()
            .expectBody(String.class).consumeWith(r ->
                assertThat(r.getResponseBody()).contains(TENANT_A));

        client.get().uri("/api/v1/probe").header("X-Tenant-ID", TENANT_B)
            .exchange().expectStatus().isOk()
            .expectBody(String.class).consumeWith(r -> {
                assertThat(r.getResponseBody()).contains(TENANT_B);
                assertThat(r.getResponseBody()).doesNotContain(TENANT_A);
            });
    }

    @Test
    void platformPath_bypassesTenantRequirement() {
        // /api/v1/audit is on the exempt list — should succeed even without a header.
        // 404 is acceptable (no handler) but NOT 400 — that would mean the filter rejected it.
        var response = client.get().uri("/api/v1/audit")
            .exchange()
            .expectBody().returnResult();
        assertThat(response.getStatus().value())
            .as("audit path must not be tenant-rejected")
            .isNotEqualTo(400);
    }

    @Test
    void actuatorPath_bypassesTenantRequirement() {
        var response = client.get().uri("/actuator/health")
            .exchange()
            .expectBody().returnResult();
        assertThat(response.getStatus().value())
            .as("actuator path must not be tenant-rejected")
            .isNotEqualTo(400);
    }
}
