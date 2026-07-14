package com.medfund.finance.controller;

import com.medfund.finance.dto.NoteDtos.CreateNoteRequest;
import com.medfund.finance.dto.NoteDtos.NoteResponse;
import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.entity.CreditNote;
import com.medfund.finance.entity.DebitNote;
import com.medfund.finance.repository.CreditNoteRepository;
import com.medfund.finance.repository.DebitNoteRepository;
import com.medfund.finance.repository.NoteQueryRepository;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Debit / Credit Notes", description = "Manual finance write-offs and goodwill credits.")
@SecurityRequirement(name = "bearer-jwt")
public class NotesController {

    private final DebitNoteRepository debitRepo;
    private final CreditNoteRepository creditRepo;
    private final NoteQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;

    @GetMapping("/api/v1/debit-notes")
    @Operation(summary = "List debit notes (unpaginated — prefer /page)")
    public Flux<NoteResponse> listDebit() {
        return debitRepo.findAllOrdered().map(NoteResponse::from);
    }

    @GetMapping("/api/v1/debit-notes/page")
    @Operation(summary = "Server-side paginated debit-notes list")
    @ApiResponse(responseCode = "200", description = "Page of debit notes")
    public Mono<PageResponse<NoteResponse>> pageDebit(
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return pageNotes(NoteQueryRepository.NoteTable.DEBIT, currencyCode, q, sortKey, sortDirection, page, size);
    }

    @PostMapping("/api/v1/debit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a debit note")
    public Mono<NoteResponse> createDebit(@Valid @RequestBody CreateNoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        var entity = new DebitNote();
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setReference(request.reference());
        entity.setTaskId(request.taskId());
        entity.setNotes(request.notes());
        return debitRepo.save(entity)
            .flatMap(saved -> publishAudit("DebitNote", saved.getId(), saved.getReference(), snapshot(saved.getAmount(), saved.getCurrencyCode(), saved.getReference()), jwt)
                .thenReturn(saved))
            .map(NoteResponse::from);
    }

    @GetMapping("/api/v1/credit-notes")
    @Operation(summary = "List credit notes (unpaginated — prefer /page)")
    public Flux<NoteResponse> listCredit() {
        return creditRepo.findAllOrdered().map(NoteResponse::from);
    }

    @GetMapping("/api/v1/credit-notes/page")
    @Operation(summary = "Server-side paginated credit-notes list")
    @ApiResponse(responseCode = "200", description = "Page of credit notes")
    public Mono<PageResponse<NoteResponse>> pageCredit(
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return pageNotes(NoteQueryRepository.NoteTable.CREDIT, currencyCode, q, sortKey, sortDirection, page, size);
    }

    private Mono<PageResponse<NoteResponse>> pageNotes(NoteQueryRepository.NoteTable table,
                                                         String currencyCode, String q,
                                                         String sortKey, String sortDirection,
                                                         int page, int size) {
        var params = new NoteFilterParams(currencyCode, q, sortKey, sortDirection, page, size);
        int pageIdx = Math.max(params.page(), 0);
        int sizeCl = Math.min(Math.max(params.size(), 1), 200);
        int offset = pageIdx * sizeCl;
        return queryRepository.search(table, params, sizeCl, offset)
                .collectList()
                .zipWith(queryRepository.count(table, params))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), pageIdx, sizeCl));
    }

    @PostMapping("/api/v1/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a credit note")
    public Mono<NoteResponse> createCredit(@Valid @RequestBody CreateNoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        var entity = new CreditNote();
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setReference(request.reference());
        entity.setTaskId(request.taskId());
        entity.setNotes(request.notes());
        return creditRepo.save(entity)
            .flatMap(saved -> publishAudit("CreditNote", saved.getId(), saved.getReference(), snapshot(saved.getAmount(), saved.getCurrencyCode(), saved.getReference()), jwt)
                .thenReturn(saved))
            .map(NoteResponse::from);
    }

    private Map<String, Object> snapshot(java.math.BigDecimal amount, String currency, String reference) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("amount", amount.toPlainString());
        snap.put("currencyCode", currency);
        snap.put("reference", reference);
        return snap;
    }

    private Mono<Void> publishAudit(String entityType, UUID id, String entityName, Map<String, Object> after, Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                entityType,
                id.toString(),
                entityName,
                "CREATE",
                actorId,
                actorEmail,
                null,
                after,
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
