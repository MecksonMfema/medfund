package com.medfund.user.controller;

import com.medfund.user.config.SecurityConfig;
import com.medfund.user.entity.Dependant;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.GlobalExceptionHandler;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(BeneficiaryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class BeneficiaryControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    MemberRepository memberRepository;

    @MockBean
    DependantRepository dependantRepository;

    @Test
    void search_returnsBothMembersAndDependants_withSponsorEnrichment() {
        // The whole point of the unified endpoint: a single query yields
        // one flat list mixing members and dependants. If either half
        // silently drops out, the claim-capture UI loses visibility of a
        // whole class of beneficiary and the operator has to guess where
        // the dependant went.
        UUID sponsorId = UUID.randomUUID();

        Member sponsor = new Member();
        sponsor.setId(sponsorId);
        sponsor.setMemberNumber("MBR-000201");
        sponsor.setFirstName("Tapiwa");
        sponsor.setLastName("Zulu");

        Dependant dep = new Dependant();
        dep.setId(UUID.randomUUID());
        dep.setMemberId(sponsorId);
        dep.setMemberNumber("MBR-000201-02");
        dep.setFirstName("Sarah");
        dep.setLastName("Zulu");

        Member memberHit = new Member();
        memberHit.setId(UUID.randomUUID());
        memberHit.setMemberNumber("MBR-000123");
        memberHit.setFirstName("Sarah");
        memberHit.setLastName("Nkomo");

        when(memberRepository.search(anyString())).thenReturn(Flux.just(memberHit));
        when(dependantRepository.search(anyString())).thenReturn(Flux.just(dep));
        when(memberRepository.findById(sponsorId)).thenReturn(Mono.just(sponsor));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/beneficiaries/search?q=sarah")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Members come first — reflects the common capture path.
                .jsonPath("$[0].kind").isEqualTo("MEMBER")
                .jsonPath("$[0].memberNumber").isEqualTo("MBR-000123")
                .jsonPath("$[0].sponsorId").doesNotExist()
                // Dependants follow, enriched with sponsor identity so the
                // UI can render "Sarah Zulu — dep of Tapiwa Zulu (MBR-000201)".
                .jsonPath("$[1].kind").isEqualTo("DEPENDANT")
                .jsonPath("$[1].memberNumber").isEqualTo("MBR-000201-02")
                .jsonPath("$[1].sponsorName").isEqualTo("Tapiwa Zulu")
                .jsonPath("$[1].sponsorMemberNumber").isEqualTo("MBR-000201");
    }

    @Test
    void search_orphanDependant_stillSurfacesWithNullSponsor() {
        // Data-consistency escape hatch — if a dependant's sponsor row
        // has been deleted or is otherwise unreachable, the dependant
        // must still appear in the results (surfacing an orphan is
        // strictly better than silently dropping it, which would make it
        // invisible to the capture flow).
        UUID missingSponsorId = UUID.randomUUID();

        Dependant orphan = new Dependant();
        orphan.setId(UUID.randomUUID());
        orphan.setMemberId(missingSponsorId);
        orphan.setMemberNumber("MBR-999999-02");
        orphan.setFirstName("Lonely");
        orphan.setLastName("Child");

        when(memberRepository.search(anyString())).thenReturn(Flux.empty());
        when(dependantRepository.search(anyString())).thenReturn(Flux.just(orphan));
        when(memberRepository.findById(missingSponsorId)).thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/beneficiaries/search?q=lonely")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].kind").isEqualTo("DEPENDANT")
                .jsonPath("$[0].memberNumber").isEqualTo("MBR-999999-02")
                // Sponsor fields absent (defaultIfEmpty branch) — the UI
                // renders "no linked sponsor" rather than crashing.
                .jsonPath("$[0].sponsorName").doesNotExist();
    }

    @Test
    void search_blankQuery_returnsEmpty() {
        // Guard against a wildcard-match blowup when the input debouncer
        // fires with an empty string — the DB would happily return every
        // beneficiary in the tenant, which is both a perf hazard and an
        // information-disclosure surface.
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/beneficiaries/search?q=")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }
}
