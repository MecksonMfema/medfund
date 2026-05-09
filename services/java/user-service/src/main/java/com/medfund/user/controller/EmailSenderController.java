package com.medfund.user.controller;

import com.medfund.user.dto.EmailSenderResponse;
import com.medfund.user.dto.UpsertEmailSenderRequest;
import com.medfund.user.service.EmailSenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/api/v1/email-senders")
@RequiredArgsConstructor
@Tag(name = "Email Senders",
        description = "Tenant-registered outbound email addresses. Only verified senders can be used by " +
                "notification campaigns. Verification is currently admin-driven; an operator flips status " +
                "after performing DNS / domain checks out-of-band.")
@SecurityRequirement(name = "bearer-jwt")
public class EmailSenderController {

    private final EmailSenderService service;

    @GetMapping
    @Operation(summary = "List all email senders for the current tenant")
    public Flux<EmailSenderResponse> list() {
        return service.findAll().map(EmailSenderResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an email sender by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sender returned"),
            @ApiResponse(responseCode = "404", description = "Sender not found"),
    })
    public Mono<EmailSenderResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(EmailSenderResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new sender (status defaults to pending)")
    public Mono<EmailSenderResponse> create(@Valid @RequestBody UpsertEmailSenderRequest request,
                                             Principal principal) {
        return service.create(request, principal != null ? principal.getName() : null)
                .map(EmailSenderResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update sender display name / notes")
    public Mono<EmailSenderResponse> update(@PathVariable UUID id,
                                             @Valid @RequestBody UpsertEmailSenderRequest request,
                                             Principal principal) {
        return service.update(id, request, principal != null ? principal.getName() : null)
                .map(EmailSenderResponse::from);
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "Mark a sender as verified")
    public Mono<EmailSenderResponse> verify(@PathVariable UUID id, Principal principal) {
        return service.verify(id, principal != null ? principal.getName() : null)
                .map(EmailSenderResponse::from);
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke a sender (cannot be used by future campaigns)")
    public Mono<EmailSenderResponse> revoke(@PathVariable UUID id, Principal principal) {
        return service.revoke(id, principal != null ? principal.getName() : null)
                .map(EmailSenderResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a sender record")
    public Mono<Void> delete(@PathVariable UUID id, Principal principal) {
        return service.delete(id, principal != null ? principal.getName() : null);
    }
}
