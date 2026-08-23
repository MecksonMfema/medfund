package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.BulkReassignItem;
import com.medfund.finance.producer.dto.BulkReassignReport;
import com.medfund.finance.producer.dto.BulkReassignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Batches member reassignments post producer termination. Each member reassignment
 * is its own transaction inside
 * {@link MemberProducerAssignmentService#assign(UUID, AssignMemberRequest, String, String)}
 * — a single failing row does not fail the batch, and the caller receives a
 * per-row {@link BulkReassignItem} in the report.
 *
 * <p>Concurrency is bounded at 8 in-flight to keep the R2DBC pool healthy;
 * effective_from snaps to 1st-of-month per
 * {@code feedback_effective_date_snap}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkReassignService {

    private static final int CONCURRENCY = 8;

    private final MemberProducerAssignmentService assignmentService;

    public Mono<BulkReassignReport> reassignBatch(BulkReassignRequest req,
                                                   String actorId, String actorEmail) {
        LocalDate snapped = req.effectiveFrom().withDayOfMonth(1);
        return Flux.fromIterable(req.memberIds())
                .flatMapSequential(memberId -> {
                    AssignMemberRequest one = new AssignMemberRequest(
                            req.newProducerId(), snapped, req.changeReason());
                    return assignmentService.assign(memberId, one, actorId, actorEmail)
                            .map(resp -> new BulkReassignItem(memberId, true, null))
                            .onErrorResume(e -> {
                                log.warn("Bulk-reassign row failed memberId={} newProducerId={} reason={}",
                                        memberId, req.newProducerId(), e.getMessage());
                                return Mono.just(new BulkReassignItem(memberId, false,
                                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            });
                }, CONCURRENCY)
                .collectList()
                .map(BulkReassignReport::from);
    }
}
