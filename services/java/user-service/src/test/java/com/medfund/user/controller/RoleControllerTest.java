package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.dto.RoleResponse;
import com.medfund.user.entity.Role;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(RoleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class RoleControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    RoleService roleService;

    @Test
    void findAll_returns200() {
        when(roleService.findAll()).thenReturn(Flux.just(createTestRole()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/roles")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        RoleResponse response = new RoleResponse(
                id, "admin", "Administrator", "Full access", false,
                List.of(), null, null
        );
        when(roleService.findByIdWithPermissions(id)).thenReturn(Mono.just(response));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/roles/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(roleService.create(any(), any(), any())).thenReturn(Mono.just(createTestRole()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"admin\",\"displayName\":\"Administrator\",\"description\":\"Full access\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Role createTestRole() {
        var r = new Role();
        r.setId(UUID.randomUUID());
        r.setName("admin");
        r.setDisplayName("Administrator");
        r.setIsSystem(false);
        return r;
    }
}
