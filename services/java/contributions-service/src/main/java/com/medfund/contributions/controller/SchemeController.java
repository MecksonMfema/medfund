package com.medfund.contributions.controller;

import com.medfund.contributions.dto.*;
import com.medfund.contributions.service.SchemeService;
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
@RequestMapping("/api/v1/schemes")
@Tag(name = "Schemes", description = "Insurance scheme, benefit, and age group management")
@SecurityRequirement(name = "bearer-jwt")
public class SchemeController {

    private final SchemeService schemeService;
    private final com.medfund.contributions.repository.BenefitTariffCategoryRepository benefitTariffCategoryRepository;

    public SchemeController(SchemeService schemeService,
                            com.medfund.contributions.repository.BenefitTariffCategoryRepository benefitTariffCategoryRepository) {
        this.schemeService = schemeService;
        this.benefitTariffCategoryRepository = benefitTariffCategoryRepository;
    }

    @GetMapping
    @Operation(summary = "List all schemes")
    public Flux<SchemeResponse> findAll() {
        return schemeService.findAll().map(SchemeResponse::from);
    }

    @GetMapping("/page")
    @Operation(summary = "Server-side paginated, sortable, filterable scheme list",
        description = "Feeds the operational schemes list table. Sortable keys: " +
                "name, schemeType, currencyCode, status, effectiveDate, createdAt. " +
                "Anything else falls back to name ASC.")
    @ApiResponse(responseCode = "200", description = "Page of schemes")
    public Mono<PageResponse<SchemeResponse>> searchPaged(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String schemeType,
            @RequestParam(required = false, defaultValue = "name") String sortKey,
            @RequestParam(required = false, defaultValue = "asc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        SchemeFilterParams params = new SchemeFilterParams(q, status, insuranceLine, schemeType, sortKey, sortDirection, page, size);
        return schemeService.searchPaged(params)
                .map(p -> PageResponse.of(
                        p.content().stream().map(SchemeResponse::from).toList(),
                        p.total(), p.page(), p.size()));
    }

    @GetMapping("/search")
    @Operation(summary = "Search schemes by name or scheme type",
               description = "Used by the operational portal's debounced scheme picker.")
    public Flux<SchemeResponse> search(@RequestParam String q,
                                       @RequestParam(defaultValue = "10") int limit) {
        return schemeService.search(q, limit).map(SchemeResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get scheme by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme found"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> findById(@PathVariable UUID id) {
        return schemeService.findById(id).map(SchemeResponse::from);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "List schemes by status")
    public Flux<SchemeResponse> findByStatus(@PathVariable String status) {
        return schemeService.findByStatus(status).map(SchemeResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheme created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Scheme with this name already exists")
    })
    public Mono<SchemeResponse> create(@Valid @RequestBody CreateSchemeRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return schemeService.create(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme updated"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateSchemeRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return schemeService.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeResponse::from);
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme deactivated"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> deactivate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.deactivate(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeResponse::from);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a previously-deactivated scheme")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme activated"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeResponse> activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.activate(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeResponse::from);
    }

    @GetMapping("/{schemeId}/benefits")
    @Operation(summary = "List benefits for a scheme")
    public Flux<SchemeBenefitResponse> findBenefits(@PathVariable UUID schemeId) {
        return schemeService.findBenefitsBySchemeId(schemeId).map(SchemeBenefitResponse::from);
    }

    @GetMapping("/{schemeId}/product-profile")
    @Operation(summary = "Scheme product profile — tracks_member_balances + per-benefit usage modes",
        description = "Single call the claim-detail page uses to decide whether to render the utilization card " +
                "and how each row should look. Returns tracksMemberBalances plus the usage_mode of every active " +
                "benefit on the scheme. Skips inactive benefits so the payload matches what Stage 3 enforces.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product profile returned"),
        @ApiResponse(responseCode = "404", description = "Scheme not found")
    })
    public Mono<SchemeProductProfileResponse> findProductProfile(@PathVariable UUID schemeId) {
        return schemeService.findProductProfile(schemeId);
    }

    @GetMapping("/{schemeId}/benefits/page")
    @Operation(summary = "Server-side paginated, sortable, filterable benefits list",
        description = "Feeds the operational benefits list for a scheme. Sortable keys: " +
                "name, benefitType, status, waitingPeriodDays, annualLimit, dailyLimit, " +
                "eventLimit, createdAt. Anything else falls back to name ASC.")
    @ApiResponse(responseCode = "200", description = "Page of benefits")
    public Mono<PageResponse<SchemeBenefitResponse>> searchBenefitsPaged(
            @PathVariable UUID schemeId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String benefitType,
            @RequestParam(required = false, defaultValue = "name") String sortKey,
            @RequestParam(required = false, defaultValue = "asc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        SchemeBenefitFilterParams params = new SchemeBenefitFilterParams(
                schemeId, q, status, benefitType, sortKey, sortDirection, page, size);
        return schemeService.searchBenefitsPaged(params)
                .map(p -> PageResponse.of(
                        p.content().stream().map(SchemeBenefitResponse::from).toList(),
                        p.total(), p.page(), p.size()));
    }

    @PostMapping("/benefits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a scheme benefit")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheme benefit created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<SchemeBenefitResponse> createBenefit(@Valid @RequestBody CreateSchemeBenefitRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return schemeService.createBenefit(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeBenefitResponse::from);
    }

    @GetMapping("/benefits/{id}")
    @Operation(summary = "Get a scheme benefit by id",
        description = "V063 — response includes categoryIds so the benefit form can "
                    + "round-trip the tariff-category coverage on edit.")
    public Mono<SchemeBenefitResponse> findBenefitById(@PathVariable UUID id) {
        return schemeService.findBenefitById(id)
                .flatMap(b -> benefitTariffCategoryRepository.findByBenefit(id)
                        .map(com.medfund.contributions.entity.BenefitTariffCategory::getTariffCategoryId)
                        .collectList()
                        .map(categoryIds -> SchemeBenefitResponse.from(b, categoryIds)));
    }

    @PutMapping("/benefits/{id}")
    @Operation(summary = "Update an existing scheme benefit",
        description = "Currency stays inherited from the parent scheme — pass it explicitly only as a defensive check.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme benefit updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or currency mismatch with parent scheme"),
        @ApiResponse(responseCode = "404", description = "Scheme benefit not found")
    })
    public Mono<SchemeBenefitResponse> updateBenefit(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateSchemeBenefitRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return schemeService.updateBenefit(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeBenefitResponse::from);
    }

    // Hard-delete of benefits was intentionally removed: claims, invoices,
    // and tariff lookups can reference a benefit historically, so wiping
    // the row would dangle those references. Deactivate is the only
    // supported workflow — schemes and age groups follow the same rule.

    @PostMapping("/benefits/{id}/deactivate")
    @Operation(summary = "Deactivate a scheme benefit",
        description = "Soft-delete that flips the status to 'inactive' so historical references (claims, invoices) stay intact.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme benefit deactivated"),
        @ApiResponse(responseCode = "404", description = "Scheme benefit not found")
    })
    public Mono<SchemeBenefitResponse> deactivateBenefit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.deactivateBenefit(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeBenefitResponse::from);
    }

    @PostMapping("/benefits/{id}/activate")
    @Operation(summary = "Reactivate a previously-deactivated benefit")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheme benefit activated"),
        @ApiResponse(responseCode = "404", description = "Scheme benefit not found")
    })
    public Mono<SchemeBenefitResponse> activateBenefit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.activateBenefit(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(SchemeBenefitResponse::from);
    }

    @GetMapping("/{schemeId}/age-groups")
    @Operation(summary = "List age groups for a scheme")
    public Flux<AgeGroupResponse> findAgeGroups(@PathVariable UUID schemeId) {
        return schemeService.findAgeGroupsBySchemeId(schemeId).map(AgeGroupResponse::from);
    }

    @PostMapping("/age-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an age group")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Age group created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public Mono<AgeGroupResponse> createAgeGroup(@Valid @RequestBody CreateAgeGroupRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return schemeService.createAgeGroup(request, AuditActor.id(jwt), AuditActor.email(jwt)).map(AgeGroupResponse::from);
    }

    @GetMapping("/age-groups/{id}")
    @Operation(summary = "Get an age group by id")
    public Mono<AgeGroupResponse> findAgeGroupById(@PathVariable UUID id) {
        return schemeService.findAgeGroupById(id).map(AgeGroupResponse::from);
    }

    @PutMapping("/age-groups/{id}")
    @Operation(summary = "Update an existing age group",
        description = "Currency stays inherited from the parent scheme — pass it explicitly only as a defensive check.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Age group updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or currency mismatch with parent scheme"),
        @ApiResponse(responseCode = "404", description = "Age group not found")
    })
    public Mono<AgeGroupResponse> updateAgeGroup(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateAgeGroupRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return schemeService.updateAgeGroup(id, request, AuditActor.id(jwt), AuditActor.email(jwt)).map(AgeGroupResponse::from);
    }

    @PostMapping("/age-groups/{id}/deactivate")
    @Operation(summary = "Deactivate an age group",
        description = "Soft-delete that flips the status to 'inactive' so existing contributions tied to it stay intact.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Age group deactivated"),
        @ApiResponse(responseCode = "404", description = "Age group not found")
    })
    public Mono<AgeGroupResponse> deactivateAgeGroup(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.deactivateAgeGroup(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(AgeGroupResponse::from);
    }

    @PostMapping("/age-groups/{id}/activate")
    @Operation(summary = "Reactivate a previously-deactivated age group")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Age group activated"),
        @ApiResponse(responseCode = "404", description = "Age group not found")
    })
    public Mono<AgeGroupResponse> activateAgeGroup(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return schemeService.activateAgeGroup(id, AuditActor.id(jwt), AuditActor.email(jwt)).map(AgeGroupResponse::from);
    }
}
