package com.medfund.finance.controller;

import com.medfund.finance.dto.CreateNoteRequest;
import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteResponse;
import com.medfund.finance.dto.NoteRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.service.NoteService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notes")
@RequiredArgsConstructor
@Tag(name = "Notes", description = "Finance-side debit / credit / memo notes — creation, approval, application, reversal")
@SecurityRequirement(name = "bearer-jwt")
public class NoteController {

    private final NoteService noteService;

    @GetMapping("/provider/{providerId}")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS, Permissions.FINANCE_MANAGE_PAYMENTS})
    @Operation(summary = "List notes by provider")
    public Flux<NoteResponse> findByProviderId(@PathVariable UUID providerId) {
        return noteService.findByProviderId(providerId).map(NoteResponse::from);
    }

    @GetMapping("/member/{memberId}")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS, Permissions.FINANCE_MANAGE_PAYMENTS})
    @Operation(summary = "List notes by member",
        description = "Feeds the member-balance detail page under the finance Creditors surface.")
    public Flux<NoteResponse> findByMemberId(@PathVariable UUID memberId) {
        return noteService.findByMemberId(memberId).map(NoteResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List notes by status (unpaginated — prefer /page)")
    public Flux<NoteResponse> findByStatus(@PathVariable String status) {
        return noteService.findByStatus(status).map(NoteResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable notes list",
        description = "Feeds /tenant/finance/debit-notes, /credit-notes, /notes, "
                + "and /claims/tax-withheld (which pins noteType=TAX_WITHHELD). "
                + "Rows carry member + provider names joined server-side so the "
                + "tables render inline.")
    @ApiResponse(responseCode = "200", description = "Page of notes")
    public Mono<PageResponse<NoteRow>> searchPaged(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String noteType,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new NoteFilterParams(
                status, direction, noteType, providerId, memberId, currencyCode, q,
                sortKey, sortDirection, page, size);
        return noteService.searchPaged(params);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get note by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Note found"),
        @ApiResponse(responseCode = "404", description = "Note not found")
    })
    public Mono<NoteResponse> findById(@PathVariable UUID id) {
        return noteService.findById(id).map(NoteResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new note",
        description = "Creates a note with auto-generated note number. The prefix "
                + "reflects direction/type: DN- for DEBIT, CN- for CREDIT, MEMO- "
                + "for note_type='MEMO'.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Note created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<NoteResponse> create(@Valid @RequestBody CreateNoteRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return noteService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(NoteResponse::from);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending note")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Note approved"),
        @ApiResponse(responseCode = "404", description = "Note not found")
    })
    public Mono<NoteResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.approve(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(NoteResponse::from);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Apply an approved note",
        description = "Sets status=applied and stamps posted_at=now(). The note "
                + "then flows onto the payee's next payment advice.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Note applied"),
        @ApiResponse(responseCode = "404", description = "Note not found")
    })
    public Mono<NoteResponse> apply(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.apply(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(NoteResponse::from);
    }

    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse an applied note (compensating REVERSAL row)",
        description = "Inserts a compensating REVERSAL note with opposite direction "
                + "and flips the original to status=reversed. The REVERSAL row "
                + "renders on the current-period advice as an opposite-direction "
                + "line; the historic advice carrying the original stays unchanged. "
                + "Only applied notes can be reversed — pending/approved go through DELETE.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Note reversed (returns the REVERSAL row)"),
        @ApiResponse(responseCode = "409", description = "Note is not in status=applied"),
        @ApiResponse(responseCode = "404", description = "Note not found")
    })
    public Mono<NoteResponse> reverse(@PathVariable UUID id,
                                      @RequestBody(required = false) Map<String, String> body,
                                      @AuthenticationPrincipal Jwt jwt) {
        String reason = body != null ? body.get("reason") : null;
        return noteService.reverse(id, reason, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(NoteResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a pending or approved note",
        description = "Applied notes cannot be deleted — reverse them via /reverse instead.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Note deleted"),
        @ApiResponse(responseCode = "409", description = "Note is applied or reversed"),
        @ApiResponse(responseCode = "404", description = "Note not found")
    })
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
