package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Provider;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.service.ProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(ProviderController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ProviderControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    ProviderService providerService;

    @Test
    void findAll_returns200() {
        when(providerService.findAll()).thenReturn(Flux.just(createTestProvider()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/providers")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        Provider provider = createTestProvider();
        provider.setId(id);
        when(providerService.findById(id)).thenReturn(Mono.just(provider));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/providers/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_nonExisting_returns404() {
        UUID id = UUID.randomUUID();
        when(providerService.findById(id)).thenReturn(Mono.error(new ProviderNotFoundException(id)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/providers/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void onboard_returns201() {
        // anyString() doesn't match null; the mock JWT has no email claim so
        // actorEmail(jwt) resolves to null. Use any() so the stub fires either way.
        when(providerService.onboard(any(), any(), any())).thenReturn(Mono.just(createTestProvider()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"City Hospital\",\"registrationNumber\":\"PR-001\",\"specialty\":\"general\",\"email\":\"info@hospital.com\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Provider createTestProvider() {
        var p = new Provider();
        p.setId(UUID.randomUUID());
        p.setName("City Hospital");
        p.setRegistrationNumber("PR-001");
        p.setSpecialty("general");
        p.setStatus("active");
        return p;
    }
}
