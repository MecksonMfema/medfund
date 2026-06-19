package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.service.SchemeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(SchemeController.class)
@Import(SecurityConfig.class)
class SchemeControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SchemeService schemeService;

    @Test
    void findAll_returns200() {
        when(schemeService.findAll()).thenReturn(Flux.just(createTestScheme()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/schemes")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(schemeService.findById(id)).thenReturn(Mono.just(createTestScheme()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/schemes/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(schemeService.create(any(), any(), any())).thenReturn(Mono.just(createTestScheme()));

        String body = """
                {
                    "name": "Gold Plan",
                    "effectiveDate": "2026-01-01"
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Scheme createTestScheme() {
        var s = new Scheme();
        s.setId(UUID.randomUUID());
        s.setName("Gold Plan");
        s.setStatus("active");
        s.setEffectiveDate(LocalDate.of(2026, 1, 1));
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }
}
