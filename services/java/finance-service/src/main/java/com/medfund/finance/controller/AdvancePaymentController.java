package com.medfund.finance.controller;

import com.medfund.finance.dto.AdvancePaymentDtos.AdvancePaymentResponse;
import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.service.AdvancePaymentService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/advance-payments")
@RequiredArgsConstructor
@Tag(name = "Advance Payments", description = "Provider / member prepayments captured outside the regular run pipeline.")
@SecurityRequirement(name = "bearer-jwt")
public class AdvancePaymentController {

    private final AdvancePaymentService service;

    @GetMapping
    @Operation(summary = "List advance payments")
    public Flux<AdvancePaymentResponse> list(@RequestParam(required = false) UUID providerId,
                                              @RequestParam(required = false) UUID memberId) {
        if (providerId != null) return service.findByProvider(providerId).map(AdvancePaymentResponse::from);
        if (memberId != null) return service.findByMember(memberId).map(AdvancePaymentResponse::from);
        return service.findAll().map(AdvancePaymentResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an advance payment")
    public Mono<AdvancePaymentResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(AdvancePaymentResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a new advance payment")
    public Mono<AdvancePaymentResponse> create(@Valid @RequestBody CreateAdvancePaymentRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(AdvancePaymentResponse::from);
    }
}
