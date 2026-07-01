package com.medfund.contributions.controller;

import com.medfund.contributions.dto.InvoiceContributionRow;
import com.medfund.contributions.dto.InvoiceRenderPayload;
import com.medfund.contributions.dto.InvoiceResponse;
import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.exception.InvoiceNotFoundException;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.service.InvoiceListService;
import com.medfund.contributions.service.StatementService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service-to-service render endpoint used by {@code file-service} when
 * it renders the invoice PDF. Combines the three data pulls that the
 * statement page makes ({@link InvoiceResponse}, {@link StatementResponse},
 * per-invoice {@link InvoiceContributionRow}) into a single call so the
 * renderer can stay a single round-trip.
 *
 * <p>The path lives under {@code /api/v1/internal/**} which is permitted
 * without JWT in {@link com.medfund.contributions.config.SecurityConfig}.
 * It is still tenant-scoped via the shared {@code TenantWebFilter} — the
 * caller must send {@code X-Tenant-ID}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/invoices")
@RequiredArgsConstructor
@Hidden
public class InternalRenderController {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceListService invoiceListService;
    private final StatementService statementService;

    @GetMapping("/{id}/render-payload")
    public Mono<InvoiceRenderPayload> renderPayload(@PathVariable UUID id) {
        Mono<InvoiceResponse> invoice = invoiceRepository.findById(id)
                .switchIfEmpty(Mono.error(new InvoiceNotFoundException(id)))
                .map(InvoiceResponse::from);
        Mono<StatementResponse> statement = statementService.generateForInvoice(id);
        Mono<java.util.List<InvoiceContributionRow>> contributions =
                invoiceListService.contributionsFor(id).collectList();
        return Mono.zip(invoice, statement, contributions)
                .map(t -> new InvoiceRenderPayload(t.getT1(), t.getT2(), t.getT3()));
    }
}
