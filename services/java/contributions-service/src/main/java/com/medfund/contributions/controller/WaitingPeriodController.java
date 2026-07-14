package com.medfund.contributions.controller;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.SchemeChangeWaitingPeriodFilterParams;
import com.medfund.contributions.dto.SchemeChangeWaitingPeriodResponse;
import com.medfund.contributions.dto.UpsertSchemeChangeWaitingPeriodRequest;
import com.medfund.contributions.dto.UpsertWaitingPeriodRequest;
import com.medfund.contributions.dto.WaitingPeriodFilterParams;
import com.medfund.contributions.dto.WaitingPeriodResponse;
import com.medfund.contributions.dto.WaitingPeriodRow;
import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import com.medfund.contributions.service.WaitingPeriodService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * Two-pronged controller covering both waiting-period catalogues:
 * <ul>
 *   <li>{@code /api/v1/waiting-periods} — initial-enrolment per scheme.</li>
 *   <li>{@code /api/v1/scheme-change-waiting-periods} — applied on
 *       UPGRADE/DOWNGRADE between schemes.</li>
 * </ul>
 *
 * <p>Both surfaces are gated by {@code billing:manage_waiting_periods} on
 * write paths and {@code billing:view} on reads (enforced at the gateway
 * route level via {@code permissionGuard} on the operational sidebar).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Waiting Periods", description = "Initial-enrolment and scheme-change waiting periods.")
@SecurityRequirement(name = "bearer-jwt")
public class WaitingPeriodController {

    private final WaitingPeriodService service;

    // ── Initial-enrolment waiting periods ────────────────────────────────────

    @GetMapping("/waiting-periods")
    @Operation(summary = "List waiting periods (unpaginated — prefer /waiting-periods/page)")
    public Flux<WaitingPeriodResponse> list(@RequestParam(required = false) UUID schemeId) {
        return (schemeId != null ? service.listByScheme(schemeId) : service.listAll())
                .map(WaitingPeriodResponse::from);
    }

    @GetMapping("/waiting-periods/page")
    @Operation(summary = "Server-side paginated, sortable, filterable waiting-periods list",
        description = "Feeds /tenant/billing/waiting-periods. Scheme name pre-joined.")
    @ApiResponse(responseCode = "200", description = "Page of waiting-period rules")
    public Mono<PageResponse<WaitingPeriodRow>> searchPaged(
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) String conditionType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new WaitingPeriodFilterParams(schemeId, conditionType, q, sortKey, sortDirection, page, size);
        return service.searchPaged(params);
    }

    @PostMapping("/waiting-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a waiting period rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    public Mono<WaitingPeriodResponse> create(@Valid @RequestBody UpsertWaitingPeriodRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, AuditActor.id(jwt), AuditActor.email(jwt)).map(WaitingPeriodResponse::from);
    }

    @PutMapping("/waiting-periods/{id}")
    @Operation(summary = "Update a waiting period rule")
    public Mono<WaitingPeriodResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpsertWaitingPeriodRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt)).map(WaitingPeriodResponse::from);
    }

    @DeleteMapping("/waiting-periods/{id}")
    @Operation(summary = "Delete a waiting period rule")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Scheme-change waiting periods ────────────────────────────────────────

    @GetMapping("/scheme-change-waiting-periods")
    @Operation(summary = "List scheme-change waiting period rules (unpaginated — prefer /page)")
    public Flux<SchemeChangeWaitingPeriodResponse> listSchemeChange() {
        return service.listAllSchemeChange().map(SchemeChangeWaitingPeriodResponse::from);
    }

    @GetMapping("/scheme-change-waiting-periods/page")
    @Operation(summary = "Server-side paginated scheme-change waiting-periods list",
        description = "Feeds /tenant/billing/scheme-change-waiting-periods.")
    @ApiResponse(responseCode = "200", description = "Page of scheme-change rules")
    public Mono<PageResponse<SchemeChangeWaitingPeriodResponse>> searchSchemeChangePaged(
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) String benefitType,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "createdAt") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new SchemeChangeWaitingPeriodFilterParams(changeType, benefitType, activeOnly, q, sortKey, sortDirection, page, size);
        return service.searchSchemeChangePaged(params).map(pageResp -> PageResponse.of(
                pageResp.content().stream().map(SchemeChangeWaitingPeriodResponse::from).toList(),
                pageResp.total(), pageResp.page(), pageResp.size()));
    }

    @PostMapping("/scheme-change-waiting-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a scheme-change waiting period rule")
    public Mono<SchemeChangeWaitingPeriodResponse> createSchemeChange(
            @Valid @RequestBody UpsertSchemeChangeWaitingPeriodRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createSchemeChange(body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(SchemeChangeWaitingPeriodResponse::from);
    }

    @PutMapping("/scheme-change-waiting-periods/{id}")
    @Operation(summary = "Update a scheme-change waiting period rule")
    public Mono<SchemeChangeWaitingPeriodResponse> updateSchemeChange(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertSchemeChangeWaitingPeriodRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.updateSchemeChange(id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(SchemeChangeWaitingPeriodResponse::from);
    }

    @DeleteMapping("/scheme-change-waiting-periods/{id}")
    @Operation(summary = "Delete a scheme-change waiting period rule")
    public Mono<ResponseEntity<Void>> deleteSchemeChange(@PathVariable UUID id,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return service.deleteSchemeChange(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

}
