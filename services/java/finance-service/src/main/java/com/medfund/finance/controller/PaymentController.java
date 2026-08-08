package com.medfund.finance.controller;

import com.medfund.finance.dto.CreatePaymentRequest;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.dto.PaymentFilterParams;
import com.medfund.finance.dto.PaymentResponse;
import com.medfund.finance.dto.PaymentRow;
import com.medfund.finance.service.PaymentService;
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
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment creation, tracking, and status management")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    @Operation(summary = "List all payments (unpaginated — prefer /page)")
    public Flux<PaymentResponse> findAll() {
        return paymentService.findAll().map(PaymentResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable payments list",
        description = "Feeds /tenant/finance/payments. Provider name joined into every row. "
                + "Sortable keys: paymentNumber, providerName, amount, currencyCode, "
                + "paymentType, status, paidAt, createdAt.")
    @ApiResponse(responseCode = "200", description = "Page of payments")
    public Mono<PageResponse<PaymentRow>> searchPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentType,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) UUID paymentRunId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new PaymentFilterParams(
                status, paymentType, providerId, currencyCode, paymentRunId, q,
                sortKey, sortDirection, page, size);
        return paymentService.searchPaged(params);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment found"),
        @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    public Mono<PaymentResponse> findById(@PathVariable UUID id) {
        return paymentService.findById(id).map(PaymentResponse::from);
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List payments by provider")
    public Flux<PaymentResponse> findByProviderId(@PathVariable UUID providerId) {
        return paymentService.findByProviderId(providerId).map(PaymentResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List payments by status")
    public Flux<PaymentResponse> findByStatus(@PathVariable String status) {
        return paymentService.findByStatus(status).map(PaymentResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new payment",
        description = "Creates a payment record with auto-generated payment number")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentResponse::from);
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Mark payment as paid")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment marked as paid"),
        @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    public Mono<PaymentResponse> markPaid(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.markPaid(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an unpaid payment",
        description = "Paid payments cannot be cancelled — post a reversing adjustment instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment cancelled"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel a paid payment"),
        @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    public Mono<PaymentResponse> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.cancel(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(PaymentResponse::from);
    }
}
