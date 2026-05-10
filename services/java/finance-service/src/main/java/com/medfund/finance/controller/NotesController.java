package com.medfund.finance.controller;

import com.medfund.finance.dto.NoteDtos.CreateNoteRequest;
import com.medfund.finance.dto.NoteDtos.NoteResponse;
import com.medfund.finance.entity.CreditNote;
import com.medfund.finance.entity.DebitNote;
import com.medfund.finance.repository.CreditNoteRepository;
import com.medfund.finance.repository.DebitNoteRepository;
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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
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
    private final AuditPublisher auditPublisher;

    @GetMapping("/api/v1/debit-notes")
    @Operation(summary = "List debit notes")
    public Flux<NoteResponse> listDebit() {
        return debitRepo.findAllOrdered().map(NoteResponse::from);
    }

    @PostMapping("/api/v1/debit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a debit note")
    public Mono<NoteResponse> createDebit(@Valid @RequestBody CreateNoteRequest request, Principal principal) {
        var entity = new DebitNote();
        entity.setId(UUID.randomUUID());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setReference(request.reference());
        entity.setTaskId(request.taskId());
        entity.setNotes(request.notes());
        return debitRepo.save(entity)
            .flatMap(saved -> publishAudit("DebitNote", saved.getId(), snapshot(saved.getAmount(), saved.getCurrencyCode(), saved.getReference()), principal)
                .thenReturn(saved))
            .map(NoteResponse::from);
    }

    @GetMapping("/api/v1/credit-notes")
    @Operation(summary = "List credit notes")
    public Flux<NoteResponse> listCredit() {
        return creditRepo.findAllOrdered().map(NoteResponse::from);
    }

    @PostMapping("/api/v1/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a credit note")
    public Mono<NoteResponse> createCredit(@Valid @RequestBody CreateNoteRequest request, Principal principal) {
        var entity = new CreditNote();
        entity.setId(UUID.randomUUID());
        entity.setAmount(request.amount());
        entity.setCurrencyCode(request.currencyCode());
        entity.setReference(request.reference());
        entity.setTaskId(request.taskId());
        entity.setNotes(request.notes());
        return creditRepo.save(entity)
            .flatMap(saved -> publishAudit("CreditNote", saved.getId(), snapshot(saved.getAmount(), saved.getCurrencyCode(), saved.getReference()), principal)
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

    private Mono<Void> publishAudit(String entityType, UUID id, Map<String, Object> after, Principal principal) {
        String actor = principal != null ? principal.getName() : "system";
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                entityType,
                id.toString(),
                "CREATE",
                actor,
                null,
                null,
                after,
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
