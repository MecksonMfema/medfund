package com.medfund.claims.controller;

import com.medfund.claims.dto.CreateTariffCodeRequest;
import com.medfund.claims.dto.CreateTariffScheduleRequest;
import com.medfund.claims.dto.TariffCodeResponse;
import com.medfund.claims.dto.TariffScheduleResponse;
import com.medfund.claims.entity.TariffModifier;
import com.medfund.claims.service.TariffService;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tariffs")
@Tag(name = "Tariffs", description = "Tariff schedule and code management")
@SecurityRequirement(name = "bearer-jwt")
public class TariffController {

    private final TariffService tariffService;

    public TariffController(TariffService tariffService) {
        this.tariffService = tariffService;
    }

    @GetMapping("/schedules")
    @Operation(summary = "List all tariff schedules")
    public Flux<TariffScheduleResponse> findAllSchedules() {
        return tariffService.findAllSchedules().map(TariffScheduleResponse::from);
    }

    @GetMapping("/schedules/{id}")
    @Operation(summary = "Get tariff schedule by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Schedule found"),
        @ApiResponse(responseCode = "404", description = "Schedule not found")
    })
    public Mono<TariffScheduleResponse> findScheduleById(@PathVariable UUID id) {
        return tariffService.findScheduleById(id).map(TariffScheduleResponse::from);
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new tariff schedule")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Schedule created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<TariffScheduleResponse> createSchedule(@Valid @RequestBody CreateTariffScheduleRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return tariffService.createSchedule(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TariffScheduleResponse::from);
    }

    @GetMapping("/codes/schedule/{scheduleId}")
    @Operation(summary = "List tariff codes by schedule")
    public Flux<TariffCodeResponse> findCodesBySchedule(@PathVariable UUID scheduleId) {
        return tariffService.findCodesByScheduleId(scheduleId).map(TariffCodeResponse::from);
    }

    @GetMapping("/codes/{code}")
    @Operation(summary = "Get tariff code by code value")
    public Mono<TariffCodeResponse> findCodeByCode(@PathVariable String code) {
        return tariffService.findCodeByCode(code).map(TariffCodeResponse::from);
    }

    @GetMapping("/codes/search")
    @Operation(summary = "Search tariff codes by code or description")
    public Flux<TariffCodeResponse> searchCodes(@RequestParam String q) {
        return tariffService.searchCodes(q).map(TariffCodeResponse::from);
    }

    @PostMapping("/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new tariff code")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tariff code created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<TariffCodeResponse> createCode(@Valid @RequestBody CreateTariffCodeRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return tariffService.createCode(request, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TariffCodeResponse::from);
    }

    @GetMapping("/modifiers")
    @Operation(summary = "List all active tariff modifiers")
    public Flux<TariffModifier> findAllModifiers() {
        return tariffService.findAllModifiers();
    }
}
