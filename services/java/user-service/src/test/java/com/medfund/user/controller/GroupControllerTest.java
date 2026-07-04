package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Group;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.repository.GroupRepository;
import com.medfund.user.service.GroupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
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

    @MockBean
    GroupRepository groupRepository;

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
        when(groupService.create(any(), any(), any())).thenReturn(Mono.just(createTestGroup()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"email\":\"contact@acme.test\","
                        + "\"liaisonKind\":\"MEMBER\",\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    /** Liaison-only payload is accepted at the DTO layer under the relaxed rule. */
    @Test
    void create_liaisonOnly_returns201() {
        when(groupService.create(any(), any(), any())).thenReturn(Mono.just(createTestGroup()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"liaisonKind\":\"MEMBER\","
                        + "\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    /** Email-only payload is also accepted — resolver falls back to the group email. */
    @Test
    void create_emailOnly_returns201() {
        when(groupService.create(any(), any(), any())).thenReturn(Mono.just(createTestGroup()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"email\":\"billing@acme.test\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    /** Invalid liaisonKind (schema-drift attempt) still short-circuits at DTO validation. */
    @Test
    void create_invalidLiaisonKind_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"email\":\"contact@acme.test\","
                        + "\"liaisonKind\":\"OWNER\",\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** Malformed email is still caught at DTO validation. */
    @Test
    void create_invalidEmailFormat_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Acme Corp\",\"registrationNumber\":\"REG-001\","
                        + "\"email\":\"not-an-email\","
                        + "\"liaisonKind\":\"MEMBER\","
                        + "\"liaisonUserId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void action_deactivate_futureDate_passedThrough() {
        UUID id = UUID.randomUUID();
        Group group = createTestGroup();
        group.setId(id);
        when(groupService.deactivate(any(UUID.class), any(LocalDate.class), anyString(), any(), any()))
                .thenReturn(Mono.just(group));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/groups/{id}/actions/deactivate", id)
                .header("X-Tenant-ID", "test-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"effectiveDate\":\"2026-11-30\",\"reason\":\"OFFBOARDING\"}")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<LocalDate> dateCap = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(groupService).deactivate(any(UUID.class),
                dateCap.capture(), reasonCap.capture(), any(), any());
        org.assertj.core.api.Assertions.assertThat(dateCap.getValue())
                .isEqualTo(LocalDate.of(2026, 11, 30));
        org.assertj.core.api.Assertions.assertThat(reasonCap.getValue())
                .isEqualTo("OFFBOARDING");
    }

    @Test
    void listSuspendedByReason_delegatesToRepository() {
        Group g = createTestGroup();
        when(groupRepository.findSuspendedByReason("ARREARS_ESCALATION"))
                .thenReturn(Flux.just(g));

        webTestClient.mutateWith(mockJwt())
                .get().uri(uri -> uri.path("/api/v1/groups/suspended")
                        .queryParam("reason", "ARREARS_ESCALATION").build())
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Object.class).hasSize(1);
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
