package com.medfund.contributions.controller;

import com.medfund.contributions.dto.InvoiceContributionRow;
import com.medfund.contributions.dto.InvoiceResponse;
import com.medfund.contributions.dto.InvoicesPage;
import com.medfund.contributions.dto.StatementResponse;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.service.BillingService;
import com.medfund.contributions.service.InvoiceListService;
import com.medfund.contributions.service.StatementService;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoices", description = "Invoice generation, listing, statement, and PDF download")
@SecurityRequirement(name = "bearer-jwt")
public class InvoiceController {

    private final BillingService billingService;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceListService invoiceListService;
    private final StatementService statementService;
    private final WebClient fileServiceWebClient;

    public InvoiceController(BillingService billingService,
                             InvoiceRepository invoiceRepository,
                             InvoiceListService invoiceListService,
                             StatementService statementService,
                             WebClient.Builder webClientBuilder,
                             @org.springframework.beans.factory.annotation.Value("${medfund.file-service.base-url:http://localhost:3003}")
                             String fileServiceBaseUrl) {
        this.billingService = billingService;
        this.invoiceRepository = invoiceRepository;
        this.invoiceListService = invoiceListService;
        this.statementService = statementService;
        this.fileServiceWebClient = webClientBuilder.baseUrl(fileServiceBaseUrl).build();
    }

    // ── Paginated listing (the per-invoice /tenant/billing/view fronts this) ──

    @GetMapping
    @Operation(summary = "List invoices (paginated, joined with holder/scheme names)",
            description = "One row per invoice. Names always joined server-side so the UI never displays UUIDs.")
    public Mono<InvoicesPage> list(@RequestParam(required = false) Integer year,
                                    @RequestParam(required = false) Integer month,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String insuranceLine,
                                    @RequestParam(required = false) String currency,
                                    @RequestParam(required = false) String holderType,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0")  int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "issuedAt") String sortKey,
                                    @RequestParam(defaultValue = "desc")     String sortDirection) {
        return invoiceListService.list(new InvoiceListService.Filter(
                year, month, status, currency, holderType, insuranceLine, q,
                page, size, sortKey, sortDirection));
    }

    // ── Per-invoice statement (snapshot-backed, see plan §3d) ────────────────

    @GetMapping("/{id}/statement")
    @Operation(summary = "Statement for one invoice (snapshot-backed)",
            description = "Returns the opening + closing balances captured at commit time plus the " +
                    "chronological ledger of transactions in the half-open window " +
                    "[prior.committed_at, this.committed_at). No double-counting possible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement assembled from snapshot"),
            @ApiResponse(responseCode = "404", description = "Invoice not found")
    })
    public Mono<StatementResponse> statement(@PathVariable UUID id) {
        return statementService.generateForInvoice(id);
    }

    // ── Per-invoice contributions breakdown (per-scheme sub-table) ──────────

    @GetMapping("/{id}/contributions")
    @Operation(summary = "Per-invoice contribution lines, joined with member/scheme names",
            description = "Powers the statement page's per-scheme breakdown sub-table.")
    public Flux<InvoiceContributionRow> contributions(@PathVariable UUID id) {
        return invoiceListService.contributionsFor(id);
    }

    // ── PDF streaming (delegates to file-service) ───────────────────────────

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Stream the rendered invoice PDF",
            description = "Looks up invoice_pdfs for the bucket+object_key pointer, then proxies the " +
                    "MinIO object through file-service. 404 with detail when the PDF hasn't been " +
                    "rendered yet (the InvoicePdfReady consumer hasn't UPSERTed the pointer).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF stream"),
            @ApiResponse(responseCode = "404", description = "PDF not yet rendered")
    })
    public Mono<ResponseEntity<Flux<DataBuffer>>> downloadPdf(@PathVariable UUID id) {
        return loadPdfPointer(id)
                .flatMap(ptr -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    Flux<DataBuffer> body = fileServiceWebClient.get()
                            .uri(uri -> uri.path("/invoice-pdf")
                                    .queryParam("bucket", ptr.bucket())
                                    .queryParam("key",    ptr.objectKey())
                                    .queryParam("tenantId", tenantId != null ? tenantId : "")
                                    .build())
                            .retrieve()
                            .bodyToFlux(DataBuffer.class);
                    String invoiceNumber = ptr.invoiceNumber() != null ? ptr.invoiceNumber() : id.toString();
                    return Mono.just(ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_PDF)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"invoice-" + invoiceNumber + ".pdf\"")
                            .body(body));
                }))
                .switchIfEmpty(Mono.error(new com.medfund.contributions.exception.InvoiceNotFoundException(
                        "PDF not yet rendered for invoice " + id)));
    }

    private Mono<PdfPointer> loadPdfPointer(UUID invoiceId) {
        return billingService.findPdfPointer(invoiceId);
    }

    public record PdfPointer(String bucket, String objectKey, String invoiceNumber) {}

    // ── Existing endpoints (kept for back-compat) ────────────────────────────

    @GetMapping("/group/{groupId}")
    @Operation(summary = "List invoices by group")
    public Flux<InvoiceResponse> findByGroupId(@PathVariable UUID groupId) {
        return billingService.findInvoicesByGroupId(groupId).map(InvoiceResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @Operation(summary = "List invoices by member")
    public Flux<InvoiceResponse> findByMemberId(@PathVariable UUID memberId) {
        return billingService.findInvoicesByMemberId(memberId).map(InvoiceResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List invoices by status (raw — prefer GET / for the operator-facing list)")
    public Flux<InvoiceResponse> findByStatus(@PathVariable String status) {
        return invoiceRepository.findByStatus(status).map(InvoiceResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get invoice by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Invoice found"),
        @ApiResponse(responseCode = "404", description = "Invoice not found")
    })
    public Mono<InvoiceResponse> findById(@PathVariable UUID id) {
        return billingService.findInvoiceById(id).map(InvoiceResponse::from);
    }
}
