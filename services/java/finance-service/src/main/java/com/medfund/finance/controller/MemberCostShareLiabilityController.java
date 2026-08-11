package com.medfund.finance.controller;

import com.medfund.finance.dto.MemberCostShareLiabilityDetailResponse;
import com.medfund.finance.dto.MemberCostShareLiabilityRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.repository.MemberCostShareLiabilityQueryRepository;
import com.medfund.finance.repository.MemberCostShareLiabilityRepository;
import com.medfund.finance.repository.MemberCostShareSettlementRepository;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read-side endpoints for the V078 member cost-share liability ledger
 * (Phase 4 copayments). Powers the Angular
 * {@code /tenant/finance/member-liabilities} list + detail pages.
 *
 * <p>Writes are Kafka-driven from claims-service adjudications — see
 * {@code ClaimAdjudicatedConsumer}. This controller is read-only.
 */
@RestController
@RequestMapping("/api/v1/member-cost-share-liabilities")
@RequiredArgsConstructor
@Tag(name = "Member Cost-Share Liabilities",
        description = "The fund-issued 'the member owes' ledger — one row per adjudicated claim with a cost-share balance.")
@SecurityRequirement(name = "bearer-jwt")
public class MemberCostShareLiabilityController {

    private final MemberCostShareLiabilityQueryRepository queryRepository;
    private final MemberCostShareLiabilityRepository liabilityRepository;
    private final MemberCostShareSettlementRepository settlementRepository;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_MEMBER_LIABILITIES)
    @Operation(summary = "Paginated, sortable, filterable member-liabilities list",
            description = "Server-side paginated. Sortable keys: memberName, memberNumber, "
                    + "claimNumber, totalOwed, totalSettled, currencyCode, status, createdAt, updatedAt.")
    @ApiResponse(responseCode = "200", description = "Page of liability rows")
    public Mono<PageResponse<MemberCostShareLiabilityRow>> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = safePage * safeSize;
        return queryRepository
                .search(status, currencyCode, memberId, q, sortKey, sortDirection, safeSize, offset)
                .collectList()
                .zipWith(queryRepository.count(status, currencyCode, memberId, q))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), safePage, safeSize));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.FINANCE_VIEW_MEMBER_LIABILITIES)
    @Operation(summary = "Liability detail + full settlement history")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liability with its settlements"),
            @ApiResponse(responseCode = "404", description = "Liability not found")
    })
    public Mono<MemberCostShareLiabilityDetailResponse> detail(@PathVariable UUID id) {
        return liabilityRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Liability not found: " + id)))
                .flatMap(liability -> settlementRepository.findByLiabilityId(id)
                        .collectList()
                        .map(settlements -> MemberCostShareLiabilityDetailResponse.from(liability, settlements)));
    }
}
