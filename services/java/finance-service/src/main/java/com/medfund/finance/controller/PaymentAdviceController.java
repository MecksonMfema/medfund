package com.medfund.finance.controller;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.dto.PaymentAdviceRecordResponse;
import com.medfund.finance.service.PaymentAdviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-advices")
@RequiredArgsConstructor
@Tag(name = "Payment Advice", description = "Payment-advice generation and persisted history.")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentAdviceController {

    private final PaymentAdviceService paymentAdviceService;

    @GetMapping
    @Operation(summary = "List previously generated advices")
    public Flux<PaymentAdviceRecordResponse> list(@RequestParam(required = false) UUID paymentRunId,
                                                   @RequestParam(required = false) UUID providerId) {
        if (paymentRunId != null) return paymentAdviceService.findByRun(paymentRunId).map(PaymentAdviceRecordResponse::from);
        if (providerId != null) return paymentAdviceService.findByProvider(providerId).map(PaymentAdviceRecordResponse::from);
        return paymentAdviceService.findAll().map(PaymentAdviceRecordResponse::from);
    }

    @GetMapping("/run/{paymentRunId}")
    @Operation(summary = "Generate (and persist) a payment advice for a payment run")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Advice generated"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentAdvice> generate(@PathVariable UUID paymentRunId) {
        return paymentAdviceService.generateAdvice(paymentRunId);
    }
}
