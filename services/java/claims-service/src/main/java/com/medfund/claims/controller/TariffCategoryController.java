package com.medfund.claims.controller;

import com.medfund.claims.dto.TariffCategoryResponse;
import com.medfund.claims.dto.UpsertTariffCategoryRequest;
import com.medfund.claims.entity.TariffCategory;
import com.medfund.claims.repository.TariffCategoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.time.Instant;
import java.util.UUID;

/**
 * V063 tariff-categories catalogue admin surface. Reads are used by the
 * tariff form (single-select dropdown) and the scheme-benefit form
 * (multi-select for "Categories covered"). Writes are exercised from
 * the tenant-admin catalogue page.
 */
@RestController
@RequestMapping("/api/v1/tariff-categories")
@Tag(name = "Tariff Categories",
     description = "Tenant catalogue of tariff categories. Required on every tariff and every scheme benefit.")
@SecurityRequirement(name = "bearer-jwt")
public class TariffCategoryController {

    private final TariffCategoryRepository repository;

    public TariffCategoryController(TariffCategoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List tariff categories",
        description = "Returns categories in sort_order + label. Pass activeOnly=true to hide "
                    + "deactivated rows (default false so admins can un-deactivate).")
    public Flux<TariffCategoryResponse> list(@RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        var stream = activeOnly
                ? repository.findAllActiveOrderBySortOrder()
                : repository.findAllOrderBySortOrder();
        return stream.map(TariffCategoryResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Look up one category by id")
    public Mono<TariffCategoryResponse> get(@PathVariable UUID id) {
        return repository.findById(id).map(TariffCategoryResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new tariff category")
    public Mono<TariffCategoryResponse> create(@Valid @RequestBody UpsertTariffCategoryRequest req) {
        var c = new TariffCategory();
        c.setCode(req.code().trim().toUpperCase().replace(' ', '_'));
        c.setLabel(req.label().trim());
        c.setDescription(req.description());
        c.setIsCapOnly(Boolean.TRUE.equals(req.isCapOnly()));
        c.setIsActive(req.isActive() == null || Boolean.TRUE.equals(req.isActive()));
        c.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return repository.save(c).map(TariffCategoryResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing category")
    public Mono<TariffCategoryResponse> update(@PathVariable UUID id,
                                                 @Valid @RequestBody UpsertTariffCategoryRequest req) {
        return repository.findById(id)
                .flatMap(existing -> {
                    existing.setCode(req.code().trim().toUpperCase().replace(' ', '_'));
                    existing.setLabel(req.label().trim());
                    existing.setDescription(req.description());
                    if (req.isCapOnly() != null) existing.setIsCapOnly(req.isCapOnly());
                    if (req.isActive() != null) existing.setIsActive(req.isActive());
                    if (req.sortOrder() != null) existing.setSortOrder(req.sortOrder());
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .map(TariffCategoryResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a category (soft delete)",
        description = "Prefer setting isActive=false via PUT. A hard delete fails when any "
                    + "tariff_codes.category_id or benefit_tariff_categories row still references "
                    + "the category (ON DELETE RESTRICT).")
    public Mono<Void> deactivate(@PathVariable UUID id) {
        return repository.findById(id)
                .flatMap(existing -> {
                    existing.setIsActive(Boolean.FALSE);
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .then();
    }
}
