package com.medfund.finance.controller;

import com.medfund.finance.dto.PaymentAdvice;
import com.medfund.finance.service.PaymentAdviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-advices")
@RequiredArgsConstructor
@Tag(name = "Payment Advice", description = "Payment-advice generation per executed payment run.")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentAdviceController {

    private final PaymentAdviceService paymentAdviceService;

    @GetMapping("/run/{paymentRunId}")
    @Operation(summary = "Generate payment advice for a payment run")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Advice generated"),
        @ApiResponse(responseCode = "404", description = "Payment run not found")
    })
    public Mono<PaymentAdvice> generate(@PathVariable UUID paymentRunId) {
        return paymentAdviceService.generateAdvice(paymentRunId);
    }
}
