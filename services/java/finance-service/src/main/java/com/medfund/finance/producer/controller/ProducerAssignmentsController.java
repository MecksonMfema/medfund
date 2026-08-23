package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.AssignmentResponse;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Producer-side view of the assignment table: list open assignments for a
 * given producer + count. Powers the tenant-admin producer-assignments page
 * and the bulk-reassign screen (Phase 9).
 */
@RestController
@RequestMapping("/api/v1/producers/{producerId}/assignments")
@RequiredArgsConstructor
@Tag(name = "Producers — Assignments",
     description = "Open member assignments per producer.")
@SecurityRequirement(name = "bearer-jwt")
public class ProducerAssignmentsController {

    private final MemberProducerAssignmentService service;

    @GetMapping
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "List open member assignments for a producer (paged).")
    public Flux<AssignmentResponse> listOpen(@PathVariable UUID producerId,
                                              @RequestParam(defaultValue = "0")  int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.listOpenForProducer(producerId, page, size);
    }

    @GetMapping("/count")
    @RequiresPermission(Permissions.PRODUCER_VIEW)
    @Operation(summary = "Count open assignments for a producer.")
    public Mono<Long> countOpen(@PathVariable UUID producerId) {
        return service.countOpenForProducer(producerId);
    }
}
