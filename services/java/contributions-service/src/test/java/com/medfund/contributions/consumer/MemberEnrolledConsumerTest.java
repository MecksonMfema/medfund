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
class MemberEnrolledConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock ContributionRepository contributionRepository;
    @Mock SchemeRepository schemeRepository;
    @Mock LateAdjustmentService lateAdjustmentService;

    private MemberEnrolledConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MemberEnrolledConsumer(null, objectMapper,
                contributionRepository, schemeRepository, lateAdjustmentService);
        lenient().when(lateAdjustmentService.postAggregate(any(), any(), any(), any(),
                anyInt(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void processEvent_currentMonthEnrolment_skipsLateAdjustment() {
        // Enrolment date = first of the current month → the regular billing
        // cycle covers this member; no late-adjustment work needed. Guard
        // against a subtle off-by-one that would fire on every fresh
        // enrolment and flood the ledger.
        String memberId = UUID.randomUUID().toString();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1);
        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-001\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, UUID.randomUUID(), enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService, never()).postAggregate(
                any(), any(), any(), any(), anyInt(), anyString(), anyString(), anyString());
        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_missingSchemeOrDate_skipsLateAdjustment() {
        String memberId = UUID.randomUUID().toString();
        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-001\",\"groupId\":\"%s\"}",
                memberId, UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(schemeRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_backDatedEnrolment_priorMonthBilled_postsLateCharge() {
        // Enrolment 1st of prior month; billing has already committed that
        // month for the scheme. Consumer should ask LateAdjustmentService
        // for exactly one month of LATE_ENROLMENT_CHARGE.
        UUID memberId = UUID.randomUUID();
        UUID groupId  = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        // First (and only) walk step returns "1 contribution" → billed;
        // second step would fall outside the walk window.
        when(contributionRepository.countByPeriodAndLine(eq(enrolment),
                eq(enrolment.withDayOfMonth(enrolment.lengthOfMonth())), eq("HEALTH")))
                .thenReturn(Mono.just(1L));

        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-001\",\"groupId\":\"%s\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, groupId, schemeId, enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService).postAggregate(
                eq(memberId), eq(groupId), eq(schemeId),
                eq(enrolment), eq(1), eq("USD"),
                eq("LATE_ENROLMENT_CHARGE"), eq(memberId.toString()));
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        StepVerifier.create(consumer.processEvent("not valid json {{{"))
            .verifyError();
    }
}
