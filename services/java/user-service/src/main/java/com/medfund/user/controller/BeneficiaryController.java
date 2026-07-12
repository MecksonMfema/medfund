package com.medfund.user.controller;

import com.medfund.user.dto.BeneficiarySearchResult;
import com.medfund.user.entity.Dependant;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Unified typeahead for the claim-capture UI: one query, both members
 * and their dependants come back in a flat list. The frontend renders
 * the same picker but routes the pick to (memberId) or (memberId +
 * dependantId) on the claim payload based on {@code kind}.
 *
 * <p>Kept separate from {@code /members/search} so existing consumers
 * (member lookup, member-only pickers) don't accidentally start
 * receiving dependants when they weren't asking for them.
 */
@RestController
@RequestMapping("/api/v1/beneficiaries")
@Tag(name = "Beneficiaries", description = "Unified member + dependant search for claim capture")
@SecurityRequirement(name = "bearer-jwt")
public class BeneficiaryController {

    private static final int MAX_RESULTS = 20;

    private final MemberRepository memberRepository;
    private final DependantRepository dependantRepository;

    public BeneficiaryController(MemberRepository memberRepository,
                                  DependantRepository dependantRepository) {
        this.memberRepository = memberRepository;
        this.dependantRepository = dependantRepository;
    }

    @GetMapping("/search")
    @Operation(summary = "Search members and dependants together",
        description = "Case-insensitive match on first name, last name, or member number. "
                    + "Returns members first, then dependants; each hit is tagged with its kind "
                    + "so the caller can route to (memberId) or (memberId, dependantId).")
    public Flux<BeneficiarySearchResult> search(@RequestParam String q) {
        if (q == null || q.isBlank()) return Flux.empty();

        Flux<BeneficiarySearchResult> memberHits = memberRepository.search(q)
                .take(MAX_RESULTS)
                .map(m -> BeneficiarySearchResult.ofMember(
                        m.getId(), m.getMemberNumber(), m.getFirstName(), m.getLastName()));

        Flux<BeneficiarySearchResult> dependantHits = dependantRepository.search(q)
                .take(MAX_RESULTS)
                .flatMap(this::withSponsor);

        // Members first — the common case is capturing a claim for the
        // primary member; dependant follow-ups are the minority.
        return Flux.concat(memberHits, dependantHits).take(MAX_RESULTS);
    }

    /**
     * Enrich a raw dependant with its sponsoring member so the UI can
     * render "Sarah Zulu (dep of Tapiwa Zulu — MBR-000201)". Falls back
     * to null sponsor fields if the parent row is somehow missing (data
     * consistency escape hatch — better to still surface the dependant
     * than drop it from the results silently).
     */
    private Mono<BeneficiarySearchResult> withSponsor(Dependant d) {
        return memberRepository.findById(d.getMemberId())
                .map(Member.class::cast)
                .map(sponsor -> BeneficiarySearchResult.ofDependant(
                        d.getId(), d.getMemberNumber(), d.getFirstName(), d.getLastName(),
                        sponsor.getId(), sponsor.getFirstName(), sponsor.getLastName(),
                        sponsor.getMemberNumber()))
                .defaultIfEmpty(BeneficiarySearchResult.ofDependant(
                        d.getId(), d.getMemberNumber(), d.getFirstName(), d.getLastName(),
                        d.getMemberId(), null, null, null));
    }
}
