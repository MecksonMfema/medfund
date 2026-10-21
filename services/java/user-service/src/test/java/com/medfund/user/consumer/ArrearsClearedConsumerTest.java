package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.user.entity.Member;
import com.medfund.user.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrearsClearedConsumerTest {

    @Mock private MemberService memberService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ArrearsClearedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ArrearsClearedConsumer(null, objectMapper, memberService);
    }

    @Test
    void processEvent_validPayload_cancelsScheduledLapse() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String json = String.format("""
            {"event":"ARREARS_CLEARED",
             "tenantId":"%s","subjectType":"MEMBER","subjectId":"%s",
             "balance":"0.00","currencyCode":"USD"}
            """, tenantId, memberId);

        when(memberService.cancelScheduledStatus(eq(memberId), eq("lapsed"), any(), any(), any()))
                .thenReturn(Mono.just(new Member()));

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(memberService).cancelScheduledStatus(
                eq(memberId), eq("lapsed"), any(), any(), any());
    }

    @Test
    void processEvent_groupSubject_skips() {
        String json = String.format("""
            {"event":"ARREARS_CLEARED",
             "tenantId":"%s","subjectType":"GROUP","subjectId":"%s"}
            """, UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(memberService, never()).cancelScheduledStatus(any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_wrongEventType_ignored() {
        String json = """
            {"event":"SOMETHING_ELSE","subjectType":"MEMBER"}
            """;

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(memberService, never()).cancelScheduledStatus(any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_missingSubjectId_dropsSilently() {
        String json = String.format("""
            {"event":"ARREARS_CLEARED",
             "tenantId":"%s","subjectType":"MEMBER"}
            """, UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(memberService, never()).cancelScheduledStatus(any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_malformedJson_dropsSilently() {
        StepVerifier.create(consumer.processEvent("not valid json {{{")).verifyComplete();
        verify(memberService, never()).cancelScheduledStatus(any(), any(), any(), any(), any());
    }
}
