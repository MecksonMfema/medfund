package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BeneficiaryBenefitResponse;
import com.medfund.contributions.entity.BeneficiaryBenefit;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.repository.BeneficiaryBenefitRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-beneficiary benefit utilization — powers the "Used vs. limit"
 * strip on the claim-detail page. Read-only surface; writes happen
 * from claims-service on adjudication.
 */
@RestController
@RequestMapping("/api/v1/beneficiary-benefits")
@Tag(name = "Beneficiary Benefits",
     description = "Per-member and per-dependant benefit utilization vs. scheme limits")
@SecurityRequirement(name = "bearer-jwt")
public class BeneficiaryBenefitController {

    private final BeneficiaryBenefitRepository repository;
    private final SchemeBenefitRepository benefitRepository;

    public BeneficiaryBenefitController(BeneficiaryBenefitRepository repository,
                                          SchemeBenefitRepository benefitRepository) {
        this.repository = repository;
        this.benefitRepository = benefitRepository;
    }

    @GetMapping("/for")
    @Operation(summary = "List utilization rows for a beneficiary",
        description = "Pass memberId alone for the member's own benefit rows; add "
                    + "dependantId to narrow to a dependant. Returns denormalized "
                    + "rows joined with the scheme benefit metadata (name, limits, "
                    + "waiting period).")
    public Flux<BeneficiaryBenefitResponse> forBeneficiary(
            @RequestParam UUID memberId,
            @RequestParam(required = false) UUID dependantId,
            @RequestParam(required = false) Integer policyYear) {
        int year = policyYear != null ? policyYear : LocalDate.now().getYear();
        return repository.findFor(memberId, dependantId, year)
                .flatMap(this::enrich);
    }

    /**
     * Look up the scheme benefit for each utilization row so the
     * response can carry the limits + name inline. A missing benefit
     * (deleted after the beneficiary row was created) still returns
     * the row with null metadata rather than silently dropping it —
     * data drift stays visible on the page instead of being hidden.
     */
    private Mono<BeneficiaryBenefitResponse> enrich(BeneficiaryBenefit b) {
        return benefitRepository.findById(b.getBenefitId())
                .map(sb -> BeneficiaryBenefitResponse.from(b, sb))
                .defaultIfEmpty(BeneficiaryBenefitResponse.from(b, (SchemeBenefit) null));
    }
}
