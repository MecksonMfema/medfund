package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.user.client.TenantAutoLapseConfigClient;
import com.medfund.user.entity.Member;
import com.medfund.user.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrearsBreachedConsumerTest {

    @Mock private MemberService memberService;
    @Mock private TenantAutoLapseConfigClient configClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ArrearsBreachedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ArrearsBreachedConsumer(null, objectMapper, memberService, configClient);
    }

    @Test
    void processEvent_validPayload_enabled_schedulesLapse() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String json = String.format("""
            {"event":"ARREARS_THRESHOLD_BREACHED",
             "tenantId":"%s","subjectType":"MEMBER","subjectId":"%s",
             "arrearsMonths":"3","balance":"125.00","currencyCode":"USD"}
            """, tenantId, memberId);

        when(configClient.get(tenantId)).thenReturn(Mono.just(
                new TenantAutoLapseConfigClient.Snapshot(tenantId, true, 3, 7)));
        when(memberService.applyOrScheduleStatus(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new Member()));

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        ArgumentCaptor<LocalDate> dateCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(memberService).applyOrScheduleStatus(
                any(),
                any(),
                dateCap.capture(),
                any(),
                any(),
                any());
        // Grace window = 7 days from today.
        assertThat(dateCap.getValue()).isEqualTo(LocalDate.now().plusDays(7));
    }

    @Test
    void processEvent_disabledTenant_skipsLapse() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String json = String.format("""
            {"event":"ARREARS_THRESHOLD_BREACHED",
             "tenantId":"%s","subjectType":"MEMBER","subjectId":"%s"}
            """, tenantId, memberId);

        when(configClient.get(tenantId)).thenReturn(Mono.just(
                TenantAutoLapseConfigClient.Snapshot.disabled(tenantId)));

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(memberService, never()).applyOrScheduleStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_groupSubjectType_skipsInMvp() {
        String json = String.format("""
            {"event":"ARREARS_THRESHOLD_BREACHED",
             "tenantId":"%s","subjectType":"GROUP","subjectId":"%s"}
            """, UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(configClient, never()).get(any());
        verify(memberService, never()).applyOrScheduleStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_missingSubjectId_dropsSilently() {
        String json = String.format("""
            {"event":"ARREARS_THRESHOLD_BREACHED",
             "tenantId":"%s","subjectType":"MEMBER"}
            """, UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(memberService, never()).applyOrScheduleStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_wrongEventType_ignored() {
        String json = """
            {"event":"SOMETHING_ELSE","subjectType":"MEMBER"}
            """;

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(memberService, never()).applyOrScheduleStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processEvent_malformedJson_dropsSilently() {
        StepVerifier.create(consumer.processEvent("not valid json {{{")).verifyComplete();
        verify(memberService, never()).applyOrScheduleStatus(any(), any(), any(), any(), any(), any());
    }
}
