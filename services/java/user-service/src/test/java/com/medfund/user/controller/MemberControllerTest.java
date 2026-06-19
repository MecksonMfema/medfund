package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.service.MemberService;
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

@WebFluxTest(MemberController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MemberControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    MemberService memberService;

    @Test
    void findAll_returns200() {
        when(memberService.findAll()).thenReturn(Flux.just(createTestMember()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/members")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        Member member = createTestMember();
        member.setId(id);
        when(memberService.findById(id)).thenReturn(Mono.just(member));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/members/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_nonExisting_returns404() {
        UUID id = UUID.randomUUID();
        when(memberService.findById(id)).thenReturn(Mono.error(new MemberNotFoundException(id)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/members/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void enroll_returns201() {
        when(memberService.enroll(any(), anyString())).thenReturn(Mono.just(createTestMember()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"dateOfBirth\":\"1990-01-15\","
                        + "\"gender\":\"male\",\"nationalId\":\"63-1234567\","
                        + "\"email\":\"john@example.com\","
                        + "\"schemeId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void enroll_missingSchemeId_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"dateOfBirth\":\"1990-01-15\","
                        + "\"gender\":\"male\",\"nationalId\":\"63-1234567\","
                        + "\"email\":\"john@example.com\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void enroll_invalidGender_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"dateOfBirth\":\"1990-01-15\","
                        + "\"gender\":\"unknown\",\"nationalId\":\"63-1234567\","
                        + "\"email\":\"john@example.com\","
                        + "\"schemeId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void enroll_blankNationalId_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"dateOfBirth\":\"1990-01-15\","
                        + "\"gender\":\"male\",\"nationalId\":\"\","
                        + "\"email\":\"john@example.com\","
                        + "\"schemeId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void enroll_invalidEmail_returns400() {
        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"firstName\":\"John\",\"lastName\":\"Doe\",\"dateOfBirth\":\"1990-01-15\","
                        + "\"gender\":\"male\",\"nationalId\":\"63-1234567\","
                        + "\"email\":\"not-an-email\","
                        + "\"schemeId\":\"11111111-1111-1111-1111-111111111111\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private Member createTestMember() {
        var m = new Member();
        m.setId(UUID.randomUUID());
        m.setFirstName("John");
        m.setLastName("Doe");
        m.setMemberNumber("MEM-001");
        m.setStatus("active");
        m.setDateOfBirth(LocalDate.of(1990, 1, 15));
        return m;
    }
}
