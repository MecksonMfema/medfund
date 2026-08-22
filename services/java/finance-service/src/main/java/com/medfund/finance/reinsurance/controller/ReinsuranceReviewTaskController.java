package com.medfund.finance.reinsurance.controller;

import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.reinsurance.dto.AssignReviewTaskRequest;
import com.medfund.finance.reinsurance.dto.ReinsuranceReviewTaskResponse;
import com.medfund.finance.reinsurance.dto.ResolveReviewTaskRequest;
import com.medfund.finance.reinsurance.service.ReinsuranceReviewTaskService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reinsurance manual-review queue (V090). The queue is populated mainly
 * by claim-regression detection in the loss cession consumer; operators
 * work through open tasks, assign to themselves, then resolve with one
 * of: RESOLVED_VOID (cascade-voids cession + recovery), RESOLVED_KEEP
 * (closes task, leaves cession untouched), DISMISSED (false-positive).
 */
@RestController
@RequestMapping("/api/v1/reinsurance/review-tasks")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Review Queue",
     description = "Manual-review tasks — claim regression, recovery disputes, manual void requests.")
@SecurityRequirement(name = "bearer-jwt")
public class ReinsuranceReviewTaskController {

    private final ReinsuranceReviewTaskService service;

    @GetMapping
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "List review tasks (paged)",
            description = "No filter (default) returns OPEN + IN_PROGRESS oldest-first. Pass "
                        + "?status=OPEN or ?status=IN_PROGRESS or a resolved status to narrow.")
    public Mono<PageResponse<ReinsuranceReviewTaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  @Min(0)  int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        boolean narrowed = status != null && !status.isBlank();
        var content = (narrowed
                    ? service.listByStatus(status, page, size)
                    : service.listOpenQueue(page, size))
                .collectList();
        var count = narrowed ? service.countByStatus(status) : service.countOpen();
        return content.zipWith(count, (rows, total) -> PageResponse.of(rows, total, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.REINSURANCE_VIEW)
    @Operation(summary = "Get a review task by id")
    public Mono<ReinsuranceReviewTaskResponse> get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/assign")
    @RequiresPermission(Permissions.REINSURANCE_RESOLVE_REVIEW)
    @Operation(summary = "Assign a task to a user (also transitions OPEN → IN_PROGRESS)",
            description = "Idempotent per assignee — re-assigning to the same user re-emits the "
                        + "audit event but doesn't error. Already-resolved tasks return 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned"),
            @ApiResponse(responseCode = "409", description = "Task already resolved")
    })
    public Mono<ReinsuranceReviewTaskResponse> assign(@PathVariable UUID id,
                                                      @Valid @RequestBody AssignReviewTaskRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return service.assign(id, body.assigneeUserId(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/resolve")
    @RequiresPermission(Permissions.REINSURANCE_RESOLVE_REVIEW)
    @Operation(summary = "Resolve a review task",
            description = "Valid resolutions: RESOLVED_VOID (cascade-voids the linked cession + writes "
                        + "off any non-terminal recovery), RESOLVED_KEEP (closes task, leaves cession "
                        + "untouched — the operator judged the re-adjudication acceptable), DISMISSED "
                        + "(false positive). Notes are optional but strongly encouraged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resolved"),
            @ApiResponse(responseCode = "400", description = "Invalid resolution"),
            @ApiResponse(responseCode = "409", description = "Task already resolved")
    })
    public Mono<ReinsuranceReviewTaskResponse> resolve(@PathVariable UUID id,
                                                       @Valid @RequestBody ResolveReviewTaskRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.resolve(id, body.resolution(), body.notes(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
