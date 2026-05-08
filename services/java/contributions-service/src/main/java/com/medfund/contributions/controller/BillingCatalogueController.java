package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BenefitTypeResponse;
import com.medfund.contributions.dto.BillingCycleConfigResponse;
import com.medfund.contributions.dto.DunningConfigResponse;
import com.medfund.contributions.dto.PaymentMethodResponse;
import com.medfund.contributions.dto.TransactionTypeResponse;
import com.medfund.contributions.dto.UpsertBenefitTypeRequest;
import com.medfund.contributions.dto.UpsertBillingCycleConfigRequest;
import com.medfund.contributions.dto.UpsertDunningConfigRequest;
import com.medfund.contributions.dto.UpsertPaymentMethodRequest;
import com.medfund.contributions.dto.UpsertTransactionTypeRequest;
import com.medfund.contributions.service.BillingCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Tenant-admin catalogue management — benefit types, payment methods,
 * transaction types, plus the dunning and billing-cycle config singletons.
 * Lives at /api/v1/billing/catalogue/* so the gateway only needs one
 * /api/v1/billing/* proxy entry.
 */
@RestController
@RequestMapping("/api/v1/billing/catalogue")
@RequiredArgsConstructor
@Tag(name = "Billing Catalogue",
        description = "Tenant-configurable billing catalogues: benefit types, payment methods, transaction types, dunning rules, billing cycle.")
@SecurityRequirement(name = "bearer-jwt")
public class BillingCatalogueController {

    private final BillingCatalogueService service;

    // ── Benefit types ─────────────────────────────────────────────────────────

    @GetMapping("/benefit-types")
    @Operation(summary = "List benefit types")
    public Flux<BenefitTypeResponse> listBenefitTypes(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return service.listBenefitTypes(activeOnly).map(BenefitTypeResponse::from);
    }

    @PostMapping("/benefit-types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create benefit type")
    @ApiResponse(responseCode = "201", description = "Benefit type created")
    public Mono<BenefitTypeResponse> createBenefitType(@Valid @RequestBody UpsertBenefitTypeRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.createBenefitType(body, actorId(jwt), actorEmail(jwt)).map(BenefitTypeResponse::from);
    }

    @PutMapping("/benefit-types/{id}")
    @Operation(summary = "Update benefit type")
    public Mono<BenefitTypeResponse> updateBenefitType(@PathVariable UUID id,
                                                       @Valid @RequestBody UpsertBenefitTypeRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.updateBenefitType(id, body, actorId(jwt), actorEmail(jwt)).map(BenefitTypeResponse::from);
    }

    @DeleteMapping("/benefit-types/{id}")
    @Operation(summary = "Delete benefit type",
            description = "Hard-deletes the catalogue row. Existing scheme_benefits keep their snapshot label.")
    public Mono<ResponseEntity<Void>> deleteBenefitType(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.deleteBenefitType(id, actorId(jwt), actorEmail(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Payment methods ───────────────────────────────────────────────────────

    @GetMapping("/payment-methods")
    @Operation(summary = "List payment methods")
    public Flux<PaymentMethodResponse> listPaymentMethods(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return service.listPaymentMethods(activeOnly).map(PaymentMethodResponse::from);
    }

    @PostMapping("/payment-methods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create payment method")
    public Mono<PaymentMethodResponse> createPaymentMethod(@Valid @RequestBody UpsertPaymentMethodRequest body,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return service.createPaymentMethod(body, actorId(jwt), actorEmail(jwt)).map(PaymentMethodResponse::from);
    }

    @PutMapping("/payment-methods/{id}")
    @Operation(summary = "Update payment method")
    public Mono<PaymentMethodResponse> updatePaymentMethod(@PathVariable UUID id,
                                                           @Valid @RequestBody UpsertPaymentMethodRequest body,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return service.updatePaymentMethod(id, body, actorId(jwt), actorEmail(jwt)).map(PaymentMethodResponse::from);
    }

    @DeleteMapping("/payment-methods/{id}")
    @Operation(summary = "Delete payment method")
    public Mono<ResponseEntity<Void>> deletePaymentMethod(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.deletePaymentMethod(id, actorId(jwt), actorEmail(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Transaction types ─────────────────────────────────────────────────────

    @GetMapping("/transaction-types")
    @Operation(summary = "List transaction types")
    public Flux<TransactionTypeResponse> listTransactionTypes(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return service.listTransactionTypes(activeOnly).map(TransactionTypeResponse::from);
    }

    @PostMapping("/transaction-types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create transaction type")
    public Mono<TransactionTypeResponse> createTransactionType(@Valid @RequestBody UpsertTransactionTypeRequest body,
                                                               @AuthenticationPrincipal Jwt jwt) {
        return service.createTransactionType(body, actorId(jwt), actorEmail(jwt)).map(TransactionTypeResponse::from);
    }

    @PutMapping("/transaction-types/{id}")
    @Operation(summary = "Update transaction type")
    public Mono<TransactionTypeResponse> updateTransactionType(@PathVariable UUID id,
                                                               @Valid @RequestBody UpsertTransactionTypeRequest body,
                                                               @AuthenticationPrincipal Jwt jwt) {
        return service.updateTransactionType(id, body, actorId(jwt), actorEmail(jwt)).map(TransactionTypeResponse::from);
    }

    @DeleteMapping("/transaction-types/{id}")
    @Operation(summary = "Delete transaction type")
    public Mono<ResponseEntity<Void>> deleteTransactionType(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.deleteTransactionType(id, actorId(jwt), actorEmail(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Dunning config ────────────────────────────────────────────────────────

    @GetMapping("/dunning")
    @Operation(summary = "Get dunning rules")
    public Mono<DunningConfigResponse> getDunning() {
        return service.getDunningConfig().map(DunningConfigResponse::from);
    }

    @PutMapping("/dunning")
    @Operation(summary = "Update dunning rules")
    public Mono<DunningConfigResponse> upsertDunning(@Valid @RequestBody UpsertDunningConfigRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.upsertDunningConfig(body, actorId(jwt), actorEmail(jwt)).map(DunningConfigResponse::from);
    }

    // ── Billing cycle config ──────────────────────────────────────────────────

    @GetMapping("/cycle")
    @Operation(summary = "Get billing cycle config")
    public Mono<BillingCycleConfigResponse> getCycle() {
        return service.getBillingCycleConfig().map(BillingCycleConfigResponse::from);
    }

    @PutMapping("/cycle")
    @Operation(summary = "Update billing cycle config")
    public Mono<BillingCycleConfigResponse> upsertCycle(@Valid @RequestBody UpsertBillingCycleConfigRequest body,
                                                        @AuthenticationPrincipal Jwt jwt) {
        return service.upsertBillingCycleConfig(body, actorId(jwt), actorEmail(jwt)).map(BillingCycleConfigResponse::from);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String actorId(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "system";
    }

    private static String actorEmail(Jwt jwt) {
        if (jwt == null) return null;
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
