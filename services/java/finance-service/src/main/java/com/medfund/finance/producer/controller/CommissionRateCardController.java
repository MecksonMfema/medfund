package com.medfund.finance.producer.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.dto.RateCardResponse;
import com.medfund.finance.producer.dto.UpdateRateCardRequest;
import com.medfund.finance.producer.service.CommissionRateCardService;
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
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/commission/rate-cards")
@RequiredArgsConstructor
@Tag(name = "Commission — Rate Cards",
     description = "Commission rate card CRUD. Lookup-driven base commission by line + tier.")
@SecurityRequirement(name = "bearer-jwt")
public class CommissionRateCardController {

    private final CommissionRateCardService service;

    @GetMapping
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @Operation(summary = "List rate cards (paged)",
            description = "Optional active filter. Default page=0 size=50, ordered by effective_from desc.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Paged rate-card envelope")})
    public Mono<PageResponse<RateCardResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    Boolean active) {
        return service.list(page, size, active);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @Operation(summary = "Get a rate card by id")
    public Mono<RateCardResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.COMMISSION_MANAGE_RATE_CARD)
    @Operation(summary = "Create a rate card",
            description = "effectiveFrom snaps to 1st-of-month; effectiveTo snaps to last-day-of-month "
                        + "per feedback_effective_date_snap. Overlapping cards for the same (line, tier) "
                        + "are permitted — resolution is most-recent-effective.")
    public Mono<RateCardResponse> create(@Valid @RequestBody CreateRateCardRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.COMMISSION_MANAGE_RATE_CARD)
    @Operation(summary = "Update a rate card (full replace).")
    public Mono<RateCardResponse> update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateRateCardRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.COMMISSION_MANAGE_RATE_CARD)
    @Operation(summary = "Soft-close a rate card",
            description = "Sets active=false and snaps effective_to to last-day-of-month.")
    public Mono<ResponseEntity<RateCardResponse>> deactivate(@PathVariable UUID id,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return service.deactivate(id, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(ResponseEntity::ok);
    }
}
