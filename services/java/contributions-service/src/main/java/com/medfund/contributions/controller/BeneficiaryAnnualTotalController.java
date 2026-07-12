package com.medfund.contributions.controller;

import com.medfund.contributions.dto.AnnualCapUtilizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * V062 scheme annual cap ledger read surface. One row per
 * (scheme, beneficiary, policy year). Read-only — writes happen on
 * adjudication from claims-service.
 */
@RestController
@RequestMapping("/api/v1/beneficiary-annual-totals")
@Tag(name = "Beneficiary Annual Totals",
     description = "Scheme-level annual cap utilization per beneficiary per policy year")
@SecurityRequirement(name = "bearer-jwt")
public class BeneficiaryAnnualTotalController {

    private final DatabaseClient databaseClient;

    public BeneficiaryAnnualTotalController(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @GetMapping("/for")
    @Operation(summary = "Cap utilization for a beneficiary + scheme in a policy year",
        description = "Returns { consumedAmount, capAmount, currencyCode }. When the scheme "
                    + "has no annual_member_cap set, capAmount is null and the UI omits the "
                    + "annual-cap row. When no ledger row exists yet (beneficiary not yet "
                    + "consumed anything this year), consumedAmount is 0.")
    public Mono<AnnualCapUtilizationResponse> forBeneficiary(
            @RequestParam UUID memberId,
            @RequestParam UUID schemeId,
            @RequestParam(required = false) UUID dependantId,
            @RequestParam(required = false) Integer policyYear) {
        int year = policyYear != null ? policyYear : LocalDate.now().getYear();
        return databaseClient.sql("""
                SELECT s.annual_member_cap,
                       s.currency_code AS scheme_currency,
                       (SELECT bat.consumed_amount FROM beneficiary_annual_totals bat
                         WHERE bat.scheme_id = :schemeId
                           AND bat.member_id = :memberId
                           AND ((:dependantId IS NULL AND bat.dependant_id IS NULL)
                                OR bat.dependant_id = :dependantId)
                           AND bat.policy_year = :year
                        ) AS consumed,
                       (SELECT bat.currency_code FROM beneficiary_annual_totals bat
                         WHERE bat.scheme_id = :schemeId
                           AND bat.member_id = :memberId
                           AND ((:dependantId IS NULL AND bat.dependant_id IS NULL)
                                OR bat.dependant_id = :dependantId)
                           AND bat.policy_year = :year
                        ) AS ledger_currency
                  FROM schemes s
                 WHERE s.id = :schemeId
                """)
                .bind("schemeId", schemeId)
                .bind("memberId", memberId)
                .bind("dependantId", dependantId != null ? dependantId : (UUID) null)
                .bind("year", year)
                .fetch().one()
                .map(row -> {
                    BigDecimal cap = row.get("annual_member_cap") != null
                            ? new BigDecimal(row.get("annual_member_cap").toString()) : null;
                    BigDecimal consumed = row.get("consumed") != null
                            ? new BigDecimal(row.get("consumed").toString()) : BigDecimal.ZERO;
                    String currency = row.get("ledger_currency") != null
                            ? row.get("ledger_currency").toString()
                            : (row.get("scheme_currency") != null ? row.get("scheme_currency").toString() : "USD");
                    return new AnnualCapUtilizationResponse(
                            schemeId, memberId, dependantId, year, consumed, cap, currency);
                })
                .defaultIfEmpty(new AnnualCapUtilizationResponse(
                        schemeId, memberId, dependantId, year, BigDecimal.ZERO, null, "USD"));
    }
}
