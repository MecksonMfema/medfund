package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.LateAdjustmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberLifecycleConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock ContributionRepository contributionRepository;
    @Mock SchemeRepository schemeRepository;
    @Mock LateAdjustmentService lateAdjustmentService;

    private MemberLifecycleConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MemberLifecycleConsumer(null, objectMapper,
                contributionRepository, schemeRepository, lateAdjustmentService);
        lenient().when(lateAdjustmentService.postAggregate(any(), any(), any(), any(),
                anyInt(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void processEvent_nonTerminatedStatus_isNoOp() {
        // Suspended / activated events flow through the same topic but
        // aren't rebate-worthy — no scheme lookup, no adjustment. Guards
        // against a subtle "any status → post credit" regression that
        // would double-refund on every status flip.
        String json = String.format(
                "{\"event\":\"MEMBER_STATUS_CHANGED\",\"memberId\":\"%s\",\"status\":\"suspended\"}",
                UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
        verify(lateAdjustmentService, never()).postAggregate(
                any(), any(), any(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_missingSchemeOrTermDate_isNoOp() {
        // Termination without the scheme/date fields → the consumer can't
        // safely compute the rebate window; skip cleanly rather than
        // fabricate a default (which is what caused a ledger corruption
        // incident on the enrolled path before it was hardened).
        String memberId = UUID.randomUUID().toString();
        String json = String.format(
                "{\"event\":\"MEMBER_STATUS_CHANGED\",\"memberId\":\"%s\",\"status\":\"terminated\"}",
                memberId);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_futureDatedTermination_isNoOp() {
        // Termination scheduled for next month → no billed period yet
        // overlaps it. Guards against the pre-refactor bug where a
        // future date would still trigger a rebate for the current
        // month.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate future = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        String json = String.format(
                "{\"event\":\"MEMBER_STATUS_CHANGED\",\"memberId\":\"%s\",\"status\":\"terminated\","
                + "\"schemeId\":\"%s\",\"terminationDate\":\"%s\"}",
                memberId, schemeId, future);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
        verify(lateAdjustmentService, never()).postAggregate(
                any(), any(), any(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_terminationOverlapsBilledMonth_postsLateTerminationCredit() {
        // Termination on the 1st of the current month. Current month has
        // already been billed for this scheme's HEALTH line → consumer
        // posts one month of LATE_TERMINATION_CREDIT so the group's
        // invoice nets down.
        UUID memberId = UUID.randomUUID();
        UUID groupId  = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate termination = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(contributionRepository.countByPeriodAndLine(eq(termination),
                eq(termination.withDayOfMonth(termination.lengthOfMonth())), eq("HEALTH")))
                .thenReturn(Mono.just(1L));

        String json = String.format(
                "{\"event\":\"MEMBER_STATUS_CHANGED\",\"memberId\":\"%s\",\"groupId\":\"%s\","
                + "\"schemeId\":\"%s\",\"terminationDate\":\"%s\",\"status\":\"terminated\"}",
                memberId, groupId, schemeId, termination);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        // The idempotency key is the memberId — a Kafka redelivery with
        // the same payload lands at LateAdjustmentService with the same
        // sourceKey + reference, and that layer's
        // findFirstByReferenceAndTransactionType short-circuit skips the
        // double-post. Assertion below pins the sourceKey position.
        verify(lateAdjustmentService).postAggregate(
                eq(memberId), eq(groupId), eq(schemeId),
                eq(termination), eq(1), eq("USD"),
                eq("LATE_TERMINATION_CREDIT"), eq(memberId.toString()));
    }

    @Test
    void processEvent_terminationCoveringZeroBilledMonths_isNoOp() {
        // Termination is in the current month but the scheme has NEVER
        // been billed for that period (countByPeriodAndLine returns 0)
        // → nothing to rebate. Would previously fire a 0-month
        // aggregate that LateAdjustmentService silently drops — assert
        // we don't even reach postAggregate.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate termination = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(contributionRepository.countByPeriodAndLine(any(), any(), eq("HEALTH")))
                .thenReturn(Mono.just(0L));

        String json = String.format(
                "{\"event\":\"MEMBER_STATUS_CHANGED\",\"memberId\":\"%s\",\"schemeId\":\"%s\","
                + "\"terminationDate\":\"%s\",\"status\":\"terminated\"}",
                memberId, schemeId, termination);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService, never()).postAggregate(
                any(), any(), any(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        StepVerifier.create(consumer.processEvent("not valid json {{{"))
                .verifyError();
    }
}
