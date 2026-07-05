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
        // Default: current cycle not billed yet → currentMonth doesn't
        // contribute to the arrears count. Individual tests override.
        lenient().when(contributionRepository.countByPeriodAndLine(any(), any(), anyString()))
                .thenReturn(Mono.just(0L));
    }

    @Test
    void processEvent_currentMonthEnrolment_notYetBilled_skipsLateAdjustment() {
        // Enrolment = first of current month AND the current cycle hasn't
        // run yet → the regular billing cycle will pick this member up.
        // No arrears needed. Default stub returns 0 contributions so the
        // "already billed" check is false.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        lenient().when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));

        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-001\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, schemeId, enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService, never()).postAggregate(
                any(), any(), any(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void processEvent_currentMonthEnrolment_nextMonthPreBilled_posts1MonthArrears() {
        // Joshua's case (V048): tenant pre-bills — they ran August's
        // cycle during July. Joshua enrols July 1. Current month (July)
        // has no contributions yet, but August already does. Joshua
        // missed the August batch, so he needs one month of arrears.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1);
        LocalDate nextMonth = enrolment.plusMonths(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        // July: empty (default 0L stub); August: 2 rows.
        org.mockito.Mockito.doReturn(Mono.just(2L))
                .when(contributionRepository)
                .countByPeriodAndLine(eq(nextMonth),
                        eq(nextMonth.withDayOfMonth(nextMonth.lengthOfMonth())),
                        eq("HEALTH"));

        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-J\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, schemeId, enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService).postAggregate(
                eq(memberId), any(), eq(schemeId),
                eq(enrolment), eq(1), eq("USD"),
                eq("LATE_ENROLMENT_CHARGE"), eq(memberId.toString()));
    }

    @Test
    void processEvent_currentMonthEnrolment_alreadyBilled_posts1MonthArrears() {
        // Matthew's case (V048): tenant ran July's billing during June;
        // then Matthew enrols effective July 1. His current-month cycle
        // has ALREADY been committed, so the regular batch missed him.
        // Consumer must post one month of LATE_ENROLMENT_CHARGE to
        // catch him up.
        UUID memberId = UUID.randomUUID();
        UUID groupId  = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        // Current cycle: contributions already exist → 1 month of
        // arrears. Stub for the currentMonth only so other months in
        // the 12-month walk fall to the default 0L and don't inflate
        // the count.
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate currentMonthEnd = currentMonth.withDayOfMonth(currentMonth.lengthOfMonth());
        org.mockito.Mockito.doReturn(Mono.just(1L))
                .when(contributionRepository)
                .countByPeriodAndLine(eq(currentMonth), eq(currentMonthEnd), eq("HEALTH"));

        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-M\",\"groupId\":\"%s\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, groupId, schemeId, enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService).postAggregate(
                eq(memberId), eq(groupId), eq(schemeId),
                eq(enrolment), eq(1), eq("USD"),
                eq("LATE_ENROLMENT_CHARGE"), eq(memberId.toString()));
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
    void processEvent_backDatedEnrolment_postsUnconditionalArrears() {
        // Enrolment 1st of the prior month → one complete past month
        // between enrollment and current-month. No dependency on prior
        // billing runs — cover accrues from enrollment_date regardless.
        UUID memberId = UUID.randomUUID();
        UUID groupId  = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));

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
    void processEvent_backDatedThreeMonths_postsThreeMonthArrears() {
        // Fresh tenant with no prior billing history — the old gate
        // stopped at the first "unbilled" month and dropped the arrears
        // entirely. V048 removes that gate: three past months → three
        // months of premium regardless of tenant billing state.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate enrolment = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        Scheme scheme = new Scheme();
        scheme.setId(schemeId);
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));

        String json = String.format(
                "{\"event\":\"MEMBER_ENROLLED\",\"memberId\":\"%s\",\"memberNumber\":\"MEM-002\",\"schemeId\":\"%s\",\"enrollmentDate\":\"%s\"}",
                memberId, schemeId, enrolment);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(lateAdjustmentService).postAggregate(
                eq(memberId), any(), eq(schemeId),
                eq(enrolment), eq(3), eq("USD"),
                eq("LATE_ENROLMENT_CHARGE"), eq(memberId.toString()));
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        StepVerifier.create(consumer.processEvent("not valid json {{{"))
            .verifyError();
    }
}
