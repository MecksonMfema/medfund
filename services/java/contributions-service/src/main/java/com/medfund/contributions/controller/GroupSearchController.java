package com.medfund.contributions.controller;

import com.medfund.contributions.dto.GroupOption;
import com.medfund.contributions.repository.GroupRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Read-only group lookup. Lives on contributions-service so the billing
 * pages (Group Charge, future Group Statements) can drive an autocomplete
 * without waiting for the full Group CRUD slice. Full CRUD will register
 * additional endpoints under the same prefix later.
 */
@RestController
@RequestMapping("/api/v1/billing/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Read-only group lookup for billing autocompletes.")
@SecurityRequirement(name = "bearer-jwt")
public class GroupSearchController {

    private final GroupRepository groupRepository;

    @GetMapping("/search")
    @Operation(summary = "Search active groups",
            description = "Substring match on name, registration number, or contact email. Returns up to `limit` rows (default 20, capped at 50).")
    @ApiResponse(responseCode = "200", description = "Matching groups returned")
    public Flux<GroupOption> search(
            @Parameter(description = "Substring match on name / registration number / email")
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        if (q == null || q.isBlank()) {
            return groupRepository.listActive(capped).map(GroupOption::from);
        }
        return groupRepository.searchActive("%" + q.toLowerCase() + "%", capped).map(GroupOption::from);
    }
}
