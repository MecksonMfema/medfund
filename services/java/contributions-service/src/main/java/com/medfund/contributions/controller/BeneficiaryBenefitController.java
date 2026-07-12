package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BeneficiaryBenefitResponse;
import com.medfund.contributions.entity.BeneficiaryBenefit;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.repository.BeneficiaryBenefitRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Map;
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
    private final DatabaseClient databaseClient;

    public BeneficiaryBenefitController(BeneficiaryBenefitRepository repository,
                                          SchemeBenefitRepository benefitRepository,
                                          DatabaseClient databaseClient) {
        this.repository = repository;
        this.benefitRepository = benefitRepository;
        this.databaseClient = databaseClient;
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

    @PostMapping("/reseed")
    @PreAuthorize("hasAuthority('super-admin')")
    @Operation(summary = "Dev/test utility — seed missing beneficiary_benefits rows for the current year",
        description = "Runs the same INSERT ... ON CONFLICT DO NOTHING shape used by V060 and the "
                    + "yearly rollover executor, for the CURRENT calendar year, filtered to "
                    + "usage_mode <> 'NO_TRACKING' and schemes with tracks_member_balances=true. "
                    + "Idempotent — a re-run is a no-op. Restricted to super-admin because it "
                    + "touches every member row in the tenant.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Rows inserted (0 when already fully seeded)"),
        @ApiResponse(responseCode = "403", description = "Caller lacks super-admin authority")
    })
    public Mono<Map<String, Long>> reseedCurrentYear() {
        int year = LocalDate.now().getYear();
        Mono<Long> members = databaseClient.sql("""
                INSERT INTO beneficiary_benefits
                    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
                SELECT m.id, NULL, sb.id, m.scheme_id, :year, sb.currency_code
                  FROM members m
                  JOIN schemes s        ON s.id = m.scheme_id AND s.tracks_member_balances = TRUE
                  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
                 WHERE m.scheme_id IS NOT NULL
                   AND m.status IN ('active', 'enrolled')
                   AND (sb.status IS NULL OR sb.status = 'active')
                   AND sb.usage_mode <> 'NO_TRACKING'
                ON CONFLICT DO NOTHING
                """)
                .bind("year", year)
                .fetch().rowsUpdated();
        Mono<Long> dependants = databaseClient.sql("""
                INSERT INTO beneficiary_benefits
                    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
                SELECT d.member_id, d.id, sb.id, m.scheme_id, :year, sb.currency_code
                  FROM dependants d
                  JOIN members m        ON m.id = d.member_id
                  JOIN schemes s        ON s.id = m.scheme_id AND s.tracks_member_balances = TRUE
                  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
                 WHERE m.scheme_id IS NOT NULL
                   AND (d.status IS NULL OR d.status IN ('active', 'enrolled'))
                   AND (sb.status IS NULL OR sb.status = 'active')
                   AND sb.usage_mode <> 'NO_TRACKING'
                ON CONFLICT DO NOTHING
                """)
                .bind("year", year)
                .fetch().rowsUpdated();
        return members.zipWith(dependants)
                .map(t -> Map.of("membersInserted", t.getT1(), "dependantsInserted", t.getT2(),
                                  "policyYear", (long) year));
    }
}
