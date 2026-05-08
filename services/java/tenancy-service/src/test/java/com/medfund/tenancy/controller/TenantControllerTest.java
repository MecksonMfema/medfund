package com.medfund.tenancy.controller;

import com.medfund.tenancy.config.SecurityConfig;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.exception.GlobalExceptionHandler;
import com.medfund.tenancy.exception.TenantNotFoundException;
import com.medfund.tenancy.service.TenantEmailTemplateService;
import com.medfund.tenancy.service.TenantService;
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

@WebFluxTest(TenantController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TenantControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    TenantService tenantService;

    @MockBean
    TenantEmailTemplateService emailTemplateService;

    @Test
    void findAll_returns200() {
        when(tenantService.findAll()).thenReturn(Flux.just(createTestTenant()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenants")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_existing_returns200() {
        UUID id = UUID.randomUUID();
        Tenant tenant = createTestTenant();
        tenant.setId(id);
        when(tenantService.findById(id)).thenReturn(Mono.just(tenant));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenants/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_nonExisting_returns404() {
        UUID id = UUID.randomUUID();
        when(tenantService.findById(id)).thenReturn(Mono.error(new TenantNotFoundException(id)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenants/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void create_validRequest_returns201() {
        when(tenantService.create(any(), anyString(), any())).thenReturn(Mono.just(createTestTenant()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Test\",\"slug\":\"test\",\"contactEmail\":\"a@b.com\",\"countryCode\":\"US\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void suspend_returns200() {
        UUID id = UUID.randomUUID();
        Tenant tenant = createTestTenant();
        tenant.setId(id);
        tenant.setStatus("suspended");
        when(tenantService.suspend(any(UUID.class), anyString(), any())).thenReturn(Mono.just(tenant));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/tenants/{id}/suspend", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    private Tenant createTestTenant() {
        var t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName("Test");
        t.setSlug("test");
        t.setStatus("active");
        return t;
    }
}
