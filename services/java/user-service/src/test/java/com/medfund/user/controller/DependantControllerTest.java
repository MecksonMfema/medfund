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
        when(dependantService.create(any(), anyString())).thenReturn(Mono.just(createTestDependant()));

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
        when(dependantService.update(any(), any(), anyString())).thenReturn(Mono.just(updated));

        webTestClient.mutateWith(mockJwt())
                .put().uri("/api/v1/dependants/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"Janet\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
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
