package com.medfund.finance.controller;

import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.CtcPaymentResponse;
import com.medfund.finance.service.CtcPaymentService;
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
@RequestMapping("/api/v1/ctc-payments")
@RequiredArgsConstructor
@Tag(name = "CTC Payments", description = "Cash-to-cardholder transfers — group or member level.")
@SecurityRequirement(name = "bearer-jwt")
public class CtcPaymentController {

    private final CtcPaymentService service;

    @GetMapping
    @Operation(summary = "List CTC payments")
    public Flux<CtcPaymentResponse> list(@RequestParam(required = false) Boolean committed) {
        return (committed != null ? service.findByCommitted(committed) : service.findAll())
            .map(CtcPaymentResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a CTC payment")
    public Mono<CtcPaymentResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(CtcPaymentResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a new CTC payment")
    public Mono<CtcPaymentResponse> create(@Valid @RequestBody CreateCtcPaymentRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcPaymentResponse::from);
    }

    @PostMapping("/{id}/commit")
    @Operation(summary = "Commit a CTC payment")
    public Mono<CtcPaymentResponse> commit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.commit(id, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcPaymentResponse::from);
    }
}
