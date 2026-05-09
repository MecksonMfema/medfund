package com.medfund.contributions.controller;

import com.medfund.contributions.dto.SchemeChangeWaitingPeriodResponse;
import com.medfund.contributions.dto.UpsertSchemeChangeWaitingPeriodRequest;
import com.medfund.contributions.dto.UpsertWaitingPeriodRequest;
import com.medfund.contributions.dto.WaitingPeriodResponse;
import com.medfund.contributions.service.WaitingPeriodService;
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
    @Operation(summary = "List waiting periods, optionally filtered by scheme")
    public Flux<WaitingPeriodResponse> list(@RequestParam(required = false) UUID schemeId) {
        return (schemeId != null ? service.listByScheme(schemeId) : service.listAll())
                .map(WaitingPeriodResponse::from);
    }

    @PostMapping("/waiting-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a waiting period rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    public Mono<WaitingPeriodResponse> create(@Valid @RequestBody UpsertWaitingPeriodRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, actorId(jwt), actorEmail(jwt)).map(WaitingPeriodResponse::from);
    }

    @PutMapping("/waiting-periods/{id}")
    @Operation(summary = "Update a waiting period rule")
    public Mono<WaitingPeriodResponse> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpsertWaitingPeriodRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, actorId(jwt), actorEmail(jwt)).map(WaitingPeriodResponse::from);
    }

    @DeleteMapping("/waiting-periods/{id}")
    @Operation(summary = "Delete a waiting period rule")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, actorId(jwt), actorEmail(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Scheme-change waiting periods ────────────────────────────────────────

    @GetMapping("/scheme-change-waiting-periods")
    @Operation(summary = "List scheme-change waiting period rules")
    public Flux<SchemeChangeWaitingPeriodResponse> listSchemeChange() {
        return service.listAllSchemeChange().map(SchemeChangeWaitingPeriodResponse::from);
    }

    @PostMapping("/scheme-change-waiting-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a scheme-change waiting period rule")
    public Mono<SchemeChangeWaitingPeriodResponse> createSchemeChange(
            @Valid @RequestBody UpsertSchemeChangeWaitingPeriodRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createSchemeChange(body, actorId(jwt), actorEmail(jwt))
                .map(SchemeChangeWaitingPeriodResponse::from);
    }

    @PutMapping("/scheme-change-waiting-periods/{id}")
    @Operation(summary = "Update a scheme-change waiting period rule")
    public Mono<SchemeChangeWaitingPeriodResponse> updateSchemeChange(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertSchemeChangeWaitingPeriodRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.updateSchemeChange(id, body, actorId(jwt), actorEmail(jwt))
                .map(SchemeChangeWaitingPeriodResponse::from);
    }

    @DeleteMapping("/scheme-change-waiting-periods/{id}")
    @Operation(summary = "Delete a scheme-change waiting period rule")
    public Mono<ResponseEntity<Void>> deleteSchemeChange(@PathVariable UUID id,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return service.deleteSchemeChange(id, actorId(jwt), actorEmail(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String actorId(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "system";
    }

    private static String actorEmail(Jwt jwt) {
        if (jwt == null) return null;
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getClaimAsString("preferred_username");
    }
}
