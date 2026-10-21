package com.medfund.user.reports.pmb.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Support feeds for the finance-service PMB Spend regulator report. Only
 * lightweight aggregate counts live here; the paid-claim aggregate itself
 * comes from claims-service. Unqualified table names rely on the tenant
 * search_path set by {@code TenantWebFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/pmb")
@RequiredArgsConstructor
@Tag(name = "PMB support (cross-service)",
        description = "Support feeds for the finance-service PMB Spend regulator report shaper.")
@SecurityRequirement(name = "bearer-jwt")
public class PmbSupportController {

    private final DatabaseClient db;

    /**
     * Active beneficiary count (principal members + dependants) as of the
     * given date. A member counts when enrolled on or before {@code asOfDate}
     * and either not terminated or terminated after {@code asOfDate}. Same
     * predicate for dependants using {@code deactivation_effective_date}.
     */
    @GetMapping("/beneficiary-count")
    @Operation(summary = "Active beneficiary count as of a date",
            description = "Principal members + dependants active on the given date. "
                        + "Feeds the PMB Spend report's per-beneficiary ratio.")
    public Mono<BeneficiaryCountResponse> beneficiaryCount(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        Mono<Long> members = db.sql("""
                        SELECT COUNT(*) AS n
                          FROM members
                         WHERE enrollment_date <= :asOfDate
                           AND (termination_date IS NULL OR termination_date > :asOfDate)
                        """)
                .bind("asOfDate", asOfDate)
                .map((row, meta) -> orZero(row.get("n", Long.class)))
                .one()
                .onErrorResume(err -> {
                    log.warn("[pmb-beneficiary] members count failed for asOf={}: {}",
                            asOfDate, err.getMessage());
                    return Mono.just(0L);
                });

        Mono<Long> dependants = db.sql("""
                        SELECT COUNT(*) AS n
                          FROM dependants
                         WHERE (deactivation_effective_date IS NULL
                                OR deactivation_effective_date > :asOfDate)
                        """)
                .bind("asOfDate", asOfDate)
                .map((row, meta) -> orZero(row.get("n", Long.class)))
                .one()
                .onErrorResume(err -> {
                    log.warn("[pmb-beneficiary] dependants count failed for asOf={}: {}",
                            asOfDate, err.getMessage());
                    return Mono.just(0L);
                });

        return Mono.zip(members, dependants)
                .map(t -> new BeneficiaryCountResponse(t.getT1(), t.getT2(), t.getT1() + t.getT2()));
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    /** Response shape for {@link #beneficiaryCount(LocalDate)}. */
    public record BeneficiaryCountResponse(
            long principalMembers,
            long dependants,
            long totalBeneficiaries) {}
}
