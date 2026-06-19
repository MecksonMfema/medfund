package com.medfund.finance.controller;

import com.medfund.finance.dto.CreatePaymentRunRequest;
import com.medfund.finance.dto.PaymentRunItemResponse;
import com.medfund.finance.dto.PaymentRunResponse;
import com.medfund.finance.service.PaymentRunService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-runs")
@Tag(name = "Payment Runs", description = "Batch payment run creation and execution")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentRunController {

    private final PaymentRunService paymentRunService;

    public PaymentRunController(PaymentRunService paymentRunService) {
        this.paymentRunService = paymentRunService;
    }

    @GetMapping
    @Operation(summary = "List all payment runs")
    public Flux<PaymentRunResponse> findAll() {
        return paymentRunService.findAll().map(PaymentRunResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment run by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment run found"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentRunResponse> findById(@PathVariable UUID id) {
        return paymentRunService.findById(id).map(PaymentRunResponse::from);
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "Get items for a payment run")
    public Flux<PaymentRunItemResponse> findItems(@PathVariable UUID id) {
        return paymentRunService.findItems(id).map(PaymentRunItemResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new payment run",
        description = "Creates a payment run in draft status with auto-generated run number")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment run created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<PaymentRunResponse> create(@Valid @RequestBody CreatePaymentRunRequest request, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a draft payment run",
        description = "Approval gate before execute. Optional — execute also accepts draft runs.")
    public Mono<PaymentRunResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.approve(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute a payment run",
        description = "Transitions a draft or approved payment run through executing to executed")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment run executed"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentRunResponse> execute(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.execute(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a payment run",
        description = "Allowed in draft, approved, or executing states. Once executed, posting a reversing run is the path.")
    public Mono<PaymentRunResponse> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentRunService.cancel(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentRunResponse::from);
    }
}
