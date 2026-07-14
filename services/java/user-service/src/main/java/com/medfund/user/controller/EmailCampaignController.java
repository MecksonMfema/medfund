package com.medfund.user.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.user.dto.AudiencePreviewResponse;
import com.medfund.user.dto.EmailCampaignFilterParams;
import com.medfund.user.dto.EmailCampaignResponse;
import com.medfund.user.dto.EmailCampaignRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpsertEmailCampaignRequest;
import com.medfund.user.service.EmailCampaignService;
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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/email-campaigns")
@RequiredArgsConstructor
@Tag(name = "Email Campaigns",
        description = "Tenant-composed outbound email campaigns. Drafts hold subject + body + " +
                "audience filter; sending records the recipient count and sent_at. Actual SMTP " +
                "dispatch happens out-of-band in notification-service.")
@SecurityRequirement(name = "bearer-jwt")
public class EmailCampaignController {

    private final EmailCampaignService service;

    @GetMapping
    @Operation(summary = "List all campaigns (unpaginated — prefer /page)")
    public Flux<EmailCampaignResponse> list() {
        return service.findAll().map(EmailCampaignResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "List campaigns (server-side paginated)",
            description = "Rows expose the sender address + display name inline so the "
                    + "operational table never renders raw sender UUIDs. Supports status "
                    + "and senderId filters, free-text search across subject, sender "
                    + "address, and sender display name.")
    public Mono<PageResponse<EmailCampaignRow>> page(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) UUID senderId,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String sortKey,
                                                      @RequestParam(required = false) String sortDirection,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return service.searchPaged(new EmailCampaignFilterParams(
                status, senderId, q, sortKey, sortDirection, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a campaign by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Campaign returned"),
            @ApiResponse(responseCode = "404", description = "Not found"),
    })
    public Mono<EmailCampaignResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(EmailCampaignResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft campaign")
    public Mono<EmailCampaignResponse> create(@Valid @RequestBody UpsertEmailCampaignRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(EmailCampaignResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a draft campaign (rejected once sent)")
    public Mono<EmailCampaignResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpsertEmailCampaignRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(EmailCampaignResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a campaign")
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/send")
    @Operation(summary = "Mark a draft campaign as sent",
            description = "Computes recipient count from the audience filter and stamps sent_at. " +
                    "Actual SMTP dispatch happens out-of-band — this endpoint records that the " +
                    "tenant intends to send. Idempotent only for drafts.")
    public Mono<EmailCampaignResponse> send(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.send(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(EmailCampaignResponse::from);
    }

    @PostMapping("/preview-audience")
    @Operation(summary = "Preview the audience matching a filter",
            description = "Returns the count plus a 10-row sample of matching members. " +
                    "Body is the audience-filter JSON object — empty {} matches every member.")
    public Mono<AudiencePreviewResponse> previewAudience(@RequestBody String filter) {
        return service.previewAudience(filter);
    }
}
