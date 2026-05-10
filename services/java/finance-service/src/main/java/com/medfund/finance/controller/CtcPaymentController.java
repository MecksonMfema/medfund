package com.medfund.finance.controller;

import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.CtcPaymentResponse;
import com.medfund.finance.service.CtcPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
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
                                            Principal principal) {
        return service.create(request, principal != null ? principal.getName() : null)
            .map(CtcPaymentResponse::from);
    }

    @PostMapping("/{id}/commit")
    @Operation(summary = "Commit a CTC payment")
    public Mono<CtcPaymentResponse> commit(@PathVariable UUID id, Principal principal) {
        return service.commit(id, principal != null ? principal.getName() : null)
            .map(CtcPaymentResponse::from);
    }
}
