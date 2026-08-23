package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.AssignmentResponse;
import com.medfund.finance.producer.dto.BulkReassignRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkReassignServiceTest {

    @Mock MemberProducerAssignmentService assignmentService;
    @InjectMocks BulkReassignService service;

    @Test
    void reassignBatch_allSucceed_reportShowsAllSucceeded() {
        UUID newProducer = UUID.randomUUID();
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        BulkReassignRequest req = new BulkReassignRequest(newProducer, members,
                LocalDate.of(2026, 8, 15), "Post-termination shift");

        when(assignmentService.assign(any(), any(AssignMemberRequest.class), anyString(), anyString()))
                .thenAnswer(inv -> Mono.just(sampleResponse(inv.getArgument(0))));

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .assertNext(report -> {
                    assertThat(report.total()).isEqualTo(3);
                    assertThat(report.succeeded()).isEqualTo(3);
                    assertThat(report.failed()).isZero();
                    assertThat(report.items()).allMatch(i -> i.success() && i.reason() == null);
                })
                .verifyComplete();
    }

    @Test
    void reassignBatch_oneFailure_isIsolated_othersStillSucceed() {
        UUID newProducer = UUID.randomUUID();
        UUID goodA = UUID.randomUUID();
        UUID bad   = UUID.randomUUID();
        UUID goodB = UUID.randomUUID();
        BulkReassignRequest req = new BulkReassignRequest(newProducer, List.of(goodA, bad, goodB),
                LocalDate.of(2026, 8, 15), null);

        when(assignmentService.assign(eq(goodA), any(), anyString(), anyString()))
                .thenReturn(Mono.just(sampleResponse(goodA)));
        when(assignmentService.assign(eq(bad), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalArgumentException("Cannot assign to a deactivated producer")));
        when(assignmentService.assign(eq(goodB), any(), anyString(), anyString()))
                .thenReturn(Mono.just(sampleResponse(goodB)));

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .assertNext(report -> {
                    assertThat(report.total()).isEqualTo(3);
                    assertThat(report.succeeded()).isEqualTo(2);
                    assertThat(report.failed()).isEqualTo(1);
                    // Order preserved via flatMapSequential
                    assertThat(report.items().get(0).memberId()).isEqualTo(goodA);
                    assertThat(report.items().get(0).success()).isTrue();
                    assertThat(report.items().get(1).memberId()).isEqualTo(bad);
                    assertThat(report.items().get(1).success()).isFalse();
                    assertThat(report.items().get(1).reason())
                            .isEqualTo("Cannot assign to a deactivated producer");
                    assertThat(report.items().get(2).memberId()).isEqualTo(goodB);
                    assertThat(report.items().get(2).success()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void reassignBatch_effectiveFrom_snapsToFirstOfMonth() {
        UUID newProducer = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        BulkReassignRequest req = new BulkReassignRequest(newProducer, List.of(member),
                LocalDate.of(2026, 8, 15), "Reason");
        when(assignmentService.assign(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(sampleResponse(member)));

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AssignMemberRequest> cap = ArgumentCaptor.forClass(AssignMemberRequest.class);
        verify(assignmentService).assign(eq(member), cap.capture(), anyString(), anyString());
        assertThat(cap.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(cap.getValue().producerId()).isEqualTo(newProducer);
    }

    @Test
    void reassignBatch_delegatesOncePerMember() {
        UUID newProducer = UUID.randomUUID();
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        BulkReassignRequest req = new BulkReassignRequest(newProducer, members,
                LocalDate.of(2026, 8, 1), null);
        AtomicInteger calls = new AtomicInteger();
        when(assignmentService.assign(any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    return Mono.just(sampleResponse(inv.getArgument(0)));
                });

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(calls.get()).isEqualTo(5);
        verify(assignmentService, times(5)).assign(any(), any(), anyString(), anyString());
    }

    @Test
    void reassignBatch_singleFailureWithoutMessage_usesClassSimpleName() {
        UUID newProducer = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        BulkReassignRequest req = new BulkReassignRequest(newProducer, List.of(member),
                LocalDate.of(2026, 8, 1), null);
        when(assignmentService.assign(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException()));

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .assertNext(report -> {
                    assertThat(report.failed()).isEqualTo(1);
                    assertThat(report.items().get(0).reason()).isEqualTo("RuntimeException");
                })
                .verifyComplete();
    }

    @Test
    void reassignBatch_changeReason_propagatesToUnderlyingCall() {
        UUID newProducer = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        BulkReassignRequest req = new BulkReassignRequest(newProducer, List.of(member),
                LocalDate.of(2026, 8, 1), "Successor for BRK-OLD");
        when(assignmentService.assign(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(sampleResponse(member)));

        StepVerifier.create(service.reassignBatch(req, "sys", "admin@t"))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AssignMemberRequest> cap = ArgumentCaptor.forClass(AssignMemberRequest.class);
        verify(assignmentService).assign(eq(member), cap.capture(), anyString(), anyString());
        assertThat(cap.getValue().changeReason()).isEqualTo("Successor for BRK-OLD");
    }

    private AssignmentResponse sampleResponse(UUID memberId) {
        return new AssignmentResponse(UUID.randomUUID(), memberId, UUID.randomUUID(),
                LocalDate.now(), null, null, OffsetDateTime.now());
    }
}
