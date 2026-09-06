package com.medfund.finance.producer.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.ProducerResponse;
import com.medfund.finance.producer.dto.TerminateProducerRequest;
import com.medfund.finance.producer.dto.UpdateProducerRequest;
import com.medfund.finance.producer.service.ProducerService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/producers")
@RequiredArgsConstructor
@Tag(name = "Producers",
     description = "Producer / broker counterparty registry, hierarchy, and banking details.")
@SecurityRequirement(name = "bearer-jwt")
public class ProducerController {

    private final ProducerService service;

    @GetMapping
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "List producers (paged)",
            description = "Optional active filter. Default page=0 size=50, ordered by name.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Paged producer envelope")})
    public Mono<PageResponse<ProducerResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    Boolean active) {
        return service.list(page, size, active);
    }

    @GetMapping("/search")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Debounced search-select lookup by name / producer_code",
            description = "Active-only, case-insensitive substring match. Powers picker components.")
    public Flux<ProducerResponse> search(@RequestParam String q,
                                         @RequestParam(defaultValue = "20") int limit) {
        return service.search(q, limit);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Get a producer by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Producer returned"),
            @ApiResponse(responseCode = "400", description = "Not found (surfaced as bad-request per IllegalArgumentException handling)")
    })
    public Mono<ProducerResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/ancestry")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Get the producer + its parent chain to the root",
            description = "Powers hierarchy visualisation and override-commission lookups.")
    public Flux<ProducerResponse> ancestry(@PathVariable UUID id) {
        return service.ancestry(id);
    }

    @GetMapping("/{id}/children")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Direct children of a producer (one level).")
    public Flux<ProducerResponse> children(@PathVariable UUID id) {
        return service.children(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.PRODUCER_MANAGE)
    @Operation(summary = "Create a producer",
            description = "Emits an audit event under entityType=Producer, entityName=<producer_code>.")
    public Mono<ProducerResponse> create(@Valid @RequestBody CreateProducerRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.PRODUCER_MANAGE)
    @Operation(summary = "Update a producer",
            description = "Full replace. Flip active=false to soft-deactivate; hard termination "
                        + "flows through the dedicated terminate endpoint (Phase 9).")
    public Mono<ProducerResponse> update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateProducerRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/terminate")
    @RequiresPermission(Permissions.PRODUCER_TERMINATE)
    @Operation(summary = "Terminate a producer + close all open member assignments",
            description = "Snaps effective_date to last-day-of-month, flips is_active=false, "
                        + "and closes every open member_producer_assignment. No auto-successor - "
                        + "the tenant admin follows up via bulk-reassign. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Producer terminated, assignments closed"),
            @ApiResponse(responseCode = "400", description = "Producer not found"),
            @ApiResponse(responseCode = "409", description = "Producer already terminated")
    })
    public Mono<ProducerResponse> terminate(@PathVariable UUID id,
                                             @Valid @RequestBody TerminateProducerRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.terminate(id, body.effectiveDate(), AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
