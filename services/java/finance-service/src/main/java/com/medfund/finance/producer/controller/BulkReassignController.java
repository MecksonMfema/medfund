package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.BulkReassignReport;
import com.medfund.finance.producer.dto.BulkReassignRequest;
import com.medfund.finance.producer.service.BulkReassignService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Bulk-reassign endpoint invoked after a producer termination. The
 * {@code producerId} path segment identifies the source (terminated)
 * producer for audit/UX context; the body carries the {@code newProducerId}
 * that all listed members are moved to.
 */
@RestController
@RequestMapping("/api/v1/producers/{producerId}/reassign-bulk")
@RequiredArgsConstructor
@Tag(name = "Producers - Bulk reassign",
     description = "Move a batch of members from one producer to another in a single call.")
@SecurityRequirement(name = "bearer-jwt")
public class BulkReassignController {

    private final BulkReassignService bulkReassignService;

    @PostMapping
    @RequiresPermission(Permissions.PRODUCER_MANAGE)
    @Operation(summary = "Bulk-reassign members from the path producer to req.newProducerId",
            description = "Each member row is its own transaction - a single row failing does not "
                        + "fail the batch. Returns a per-row report of successes and failures.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch executed - see report.items for per-row outcome"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing newProducerId or empty memberIds")
    })
    public Mono<BulkReassignReport> reassign(@PathVariable UUID producerId,
                                              @Valid @RequestBody BulkReassignRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return bulkReassignService.reassignBatch(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
