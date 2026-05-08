package com.medfund.contributions.controller;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.dto.TransactionFilterParams;
import com.medfund.contributions.dto.TransactionResponse;
import com.medfund.contributions.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Payment transaction recording and lookup")
@SecurityRequirement(name = "bearer-jwt")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    @Operation(summary = "Search transactions",
        description = "Server-side paginated, filterable list. Every query parameter is optional. " +
                "Filter values for transactionType / paymentMethod are matched case-insensitively against " +
                "the snapshot label stored on each row.")
    @ApiResponse(responseCode = "200", description = "Page of transactions (newest first)")
    public Mono<PageResponse<TransactionResponse>> search(
            @Parameter(description = "ISO 4217 currency code") @RequestParam(required = false) String currency,
            @Parameter(description = "Catalogue label or code, case-insensitive") @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) UUID contributionId,
            @RequestParam(required = false) UUID invoiceId,
            @Parameter(description = "Substring match on transaction_number or reference") @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        TransactionFilterParams params = new TransactionFilterParams(
                currency, transactionType, paymentMethod, periodStart, periodEnd,
                contributionId, invoiceId, q, page, size);
        return transactionService.search(params)
                .map(p -> PageResponse.of(
                        p.content().stream().map(TransactionResponse::from).toList(),
                        p.total(), p.page(), p.size()));
    }

    @GetMapping("/contribution/{contributionId}")
    @Operation(summary = "List transactions by contribution")
    public Flux<TransactionResponse> findByContributionId(@PathVariable UUID contributionId) {
        return transactionService.findByContributionId(contributionId).map(TransactionResponse::from);
    }

    @GetMapping("/invoice/{invoiceId}")
    @Operation(summary = "List transactions by invoice")
    public Flux<TransactionResponse> findByInvoiceId(@PathVariable UUID invoiceId) {
        return transactionService.findByInvoiceId(invoiceId).map(TransactionResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a transaction")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Transaction recorded"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<TransactionResponse> record(@Valid @RequestBody RecordTransactionRequest request,
                                            Principal principal) {
        return transactionService.record(request, principal.getName()).map(TransactionResponse::from);
    }
}
