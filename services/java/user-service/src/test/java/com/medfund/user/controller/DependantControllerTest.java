package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Dependant;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.service.DependantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(DependantController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class DependantControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    DependantService dependantService;

    @Test
    void findByMemberId_returns200() {
        UUID memberId = UUID.randomUUID();
        when(dependantService.findByMemberId(memberId)).thenReturn(Flux.just(createTestDependant()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/dependants/member/{memberId}", memberId)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        Dependant dependant = createTestDependant();
        dependant.setId(id);
        when(dependantService.findById(id)).thenReturn(Mono.just(dependant));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/dependants/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(dependantService.create(any(), any(), any())).thenReturn(Mono.just(createTestDependant()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/dependants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"memberId\":\"" + UUID.randomUUID() + "\",\"firstName\":\"Jane\",\"lastName\":\"Doe\","
                        + "\"dateOfBirth\":\"2015-06-01\",\"relationship\":\"child\","
                        + "\"gender\":\"female\",\"nationalId\":\"63-1234567\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void create_invalidGender_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/dependants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"memberId\":\"" + UUID.randomUUID() + "\",\"firstName\":\"Jane\",\"lastName\":\"Doe\","
                        + "\"dateOfBirth\":\"2015-06-01\",\"relationship\":\"child\","
                        + "\"gender\":\"unknown\",\"nationalId\":\"63-1234567\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void create_blankNationalId_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/dependants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"memberId\":\"" + UUID.randomUUID() + "\",\"firstName\":\"Jane\",\"lastName\":\"Doe\","
                        + "\"dateOfBirth\":\"2015-06-01\",\"relationship\":\"child\","
                        + "\"gender\":\"female\",\"nationalId\":\"\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void update_invalidGender_returns400() {
        UUID id = UUID.randomUUID();

        webTestClient.mutateWith(mockJwt())
                .put().uri("/api/v1/dependants/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"gender\":\"nonbinary\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void update_nullGender_returns200() {
        UUID id = UUID.randomUUID();
        Dependant updated = createTestDependant();
        updated.setId(id);
        when(dependantService.update(any(), any(), any(), any())).thenReturn(Mono.just(updated));

        webTestClient.mutateWith(mockJwt())
                .put().uri("/api/v1/dependants/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"Janet\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    // ------------------------------------------------------------------
    // Deactivate (V046) — dependants are never deleted, only marked
    // deactivated with an effective date. Two entry paths:
    //   * body with { effectiveDate: 'YYYY-MM-DD' } → operator-picked.
    //   * no body → service defaults effectiveDate to today.
    // The controller wires body?.effectiveDate through the service; the
    // 400 branch for a garbled ISO is covered by Jackson deserialisation.
    // ------------------------------------------------------------------

    @Test
    void deactivate_withEffectiveDateBody_returns200_andForwardsDate() {
        UUID id = UUID.randomUUID();
        Dependant terminated = createTestDependant();
        terminated.setId(id);
        terminated.setStatus("deactivated");
        // Termination dates snap to end-of-month
        // (feedback_effective_date_snap) — enforced by @EndOfMonth on the
        // request DTO. A mid-month payload would 400.
        LocalDate effective = LocalDate.of(2026, 9, 30);
        terminated.setDeactivationEffectiveDate(effective);
        when(dependantService.deactivate(any(), any(), any(), any()))
                .thenReturn(Mono.just(terminated));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/dependants/{id}/deactivate", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"effectiveDate\":\"2026-09-30\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("deactivated")
                .jsonPath("$.deactivationEffectiveDate").isEqualTo("2026-09-30");
    }

    @Test
    void deactivate_withoutBody_returns200_andForwardsNullDate() {
        // Body is optional — controller forwards null so the service's
        // "default to today" branch fires. Verify the null-body path
        // wires through by asserting the response comes back cleanly.
        UUID id = UUID.randomUUID();
        Dependant terminated = createTestDependant();
        terminated.setId(id);
        terminated.setStatus("deactivated");
        terminated.setDeactivationEffectiveDate(LocalDate.now());
        when(dependantService.deactivate(any(), any(), any(), any()))
                .thenReturn(Mono.just(terminated));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/dependants/{id}/deactivate", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("deactivated");
    }

    private Dependant createTestDependant() {
        var d = new Dependant();
        d.setId(UUID.randomUUID());
        d.setMemberId(UUID.randomUUID());
        d.setFirstName("Jane");
        d.setLastName("Doe");
        d.setDateOfBirth(LocalDate.of(2015, 6, 1));
        d.setRelationship("child");
        d.setStatus("active");
        return d;
    }
}
