package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BillingCommitResponse;
import com.medfund.contributions.dto.BillingPreviewResponse;
import com.medfund.contributions.dto.CommitBillingRequest;
import com.medfund.contributions.dto.ContributionResponse;
import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.dto.PreviewBillingRequest;
import com.medfund.contributions.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contributions")
@Tag(name = "Contributions", description = "Contribution billing, payment recording, and balance tracking")
@SecurityRequirement(name = "bearer-jwt")
public class ContributionController {

    private final BillingService billingService;

    public ContributionController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List contributions by member")
    public Flux<ContributionResponse> findByMemberId(@PathVariable UUID memberId) {
        return billingService.findContributionsByMemberId(memberId).map(ContributionResponse::from);
    }

    @GetMapping("/group/{groupId}")
    @Operation(summary = "List contributions by group")
    public Flux<ContributionResponse> findByGroupId(@PathVariable UUID groupId) {
        return billingService.findContributionsByGroupId(groupId).map(ContributionResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List contributions by status")
    public Flux<ContributionResponse> findByStatus(@PathVariable String status) {
        return billingService.findContributionsByStatus(status).map(ContributionResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get contribution by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contribution found"),
        @ApiResponse(responseCode = "404", description = "Contribution not found")
    })
    public Mono<ContributionResponse> findById(@PathVariable UUID id) {
        return billingService.findContributionById(id).map(ContributionResponse::from);
    }

    @PostMapping("/preview")
    @Operation(summary = "Preview a billing run",
        description = "Resolves the population that would be billed and runs tenant pricing rules. " +
                "Returns counts, per-currency totals, and a sample. No persistence.")
    @ApiResponse(responseCode = "200", description = "Preview computed")
    public Mono<BillingPreviewResponse> previewBilling(@Valid @RequestBody PreviewBillingRequest request) {
        return billingService.previewBilling(request);
    }

    @PostMapping("/commit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Commit a billing run",
        description = "Persists contribution rows for the same selection and stamps " +
                "billing_cycle_config.last_committed_at. Returns 409 with `remainingMinutes` " +
                "if another commit ran within the configured cooldown.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Billing cycle committed"),
        @ApiResponse(responseCode = "409", description = "Cooldown active")
    })
    public Mono<BillingCommitResponse> commitBilling(@Valid @RequestBody CommitBillingRequest request,
                                                     Principal principal) {
        return billingService.commitBilling(request, principal.getName());
    }

    @PostMapping("/generate-billing")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "[Deprecated] Generate a single contribution row",
        description = "Legacy single-row endpoint. Prefer /preview + /commit which iterates the chosen members.",
        deprecated = true)
    public Mono<Long> generateBilling(@Valid @RequestBody GenerateBillingRequest request, Principal principal) {
        return billingService.generateBilling(request, principal.getName());
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Record contribution payment")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment recorded"),
        @ApiResponse(responseCode = "404", description = "Contribution not found")
    })
    public Mono<ContributionResponse> recordPayment(@PathVariable UUID id,
                                                    @RequestParam String paymentMethod,
                                                    @RequestParam(required = false) String paymentReference,
                                                    Principal principal) {
        return billingService.recordPayment(id, paymentMethod, paymentReference, principal.getName())
                .map(ContributionResponse::from);
    }
}
