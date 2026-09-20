package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Provider;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.entity.ProviderInsuranceLine;
import com.medfund.user.entity.ProviderTenant;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.service.ProviderMembershipService;
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

    @MockBean
    ProviderMembershipService membershipService;

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

    // ── Tenant membership + insurance-line endpoints (Phase 6) ──────

    @Test
    void listMemberships_returns200WithTheJunctionRows() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipService.listMemberships(id))
                .thenReturn(Flux.just(membership(id, tenantId)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/providers/{id}/tenants", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].tenantId").isEqualTo(tenantId.toString())
                .jsonPath("$[0].status").isEqualTo("active")
                .jsonPath("$[0].networkTier").isEqualTo("STANDARD");
    }

    @Test
    void link_returns201() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipService.link(any(), any(), any(), any()))
                .thenReturn(Mono.just(membership(id, tenantId)));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers/{id}/tenants/{tenantId}", id, tenantId)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void link_duplicate_returns409() {
        // ProviderMembershipService maps the composite-PK violation to
        // IllegalStateException, which GlobalExceptionHandler renders as 409.
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipService.link(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("already linked")));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers/{id}/tenants/{tenantId}", id, tenantId)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void link_unknownProvider_returns404() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipService.link(any(), any(), any(), any()))
                .thenReturn(Mono.error(new ProviderNotFoundException(id)));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers/{id}/tenants/{tenantId}", id, tenantId)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void unlink_returns204() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(membershipService.unlink(any(), any(), any(), any())).thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .delete().uri("/api/v1/providers/{id}/tenants/{tenantId}", id, tenantId)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void listLines_returns200WithTheLineCodes() {
        UUID id = UUID.randomUUID();
        when(membershipService.listLines(id)).thenReturn(Flux.just(line(id, "HEALTH"), line(id, "TRAVEL")));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/providers/{id}/insurance-lines", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0]").isEqualTo("HEALTH")
                .jsonPath("$[1]").isEqualTo("TRAVEL");
    }

    @Test
    void addLine_returns201() {
        UUID id = UUID.randomUUID();
        when(membershipService.addLine(any(), any(), any(), any()))
                .thenReturn(Mono.just(line(id, "HEALTH")));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers/{id}/insurance-lines/{line}", id, "HEALTH")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void addLine_unknownLine_returns400() {
        UUID id = UUID.randomUUID();
        when(membershipService.addLine(any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalArgumentException("Unknown insurance line: WRONG")));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/providers/{id}/insurance-lines/{line}", id, "WRONG")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void removeLine_returns204() {
        UUID id = UUID.randomUUID();
        when(membershipService.removeLine(any(), any(), any(), any())).thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .delete().uri("/api/v1/providers/{id}/insurance-lines/{line}", id, "HEALTH")
                .exchange()
                .expectStatus().isNoContent();
    }

    private ProviderTenant membership(UUID providerId, UUID tenantId) {
        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantId);
        pt.setStatus("active");
        pt.setNetworkTier("STANDARD");
        pt.setInNetwork(Boolean.TRUE);
        return pt;
    }

    private ProviderInsuranceLine line(UUID providerId, String code) {
        var l = new ProviderInsuranceLine();
        l.setProviderId(providerId);
        l.setInsuranceLine(code);
        return l;
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
