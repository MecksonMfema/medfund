package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Group;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.service.GroupService;
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

@WebFluxTest(GroupController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class GroupControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    GroupService groupService;

    @Test
    void findAll_returns200() {
        when(groupService.findAll()).thenReturn(Flux.just(createTestGroup()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/groups")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        Group group = createTestGroup();
        group.setId(id);
        when(groupService.findById(id)).thenReturn(Mono.just(group));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/groups/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(groupService.create(any(), anyString())).thenReturn(Mono.just(createTestGroup()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"liaisonKind\":\"MEMBER\",\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void create_missingLiaisonKind_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void create_invalidLiaisonKind_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"liaisonKind\":\"OWNER\",\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void create_missingLiaisonUserId_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"liaisonKind\":\"MEMBER\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private Group createTestGroup() {
        var g = new Group();
        g.setId(UUID.randomUUID());
        g.setName("Acme Corp");
        g.setRegistrationNumber("REG-001");
        g.setStatus("active");
        return g;
    }
}
