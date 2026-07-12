package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private UserEventPublisher userEventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        userEventPublisher = new UserEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMemberEnrolled_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishMemberEnrolled(
                        "mbr-1", "MBR-123", "grp-1", "scheme-1", "2026-07-01", "1990-01-01"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.users.member-enrolled");
                    assertThat(record.key()).isEqualTo("mbr-1");
                    assertThat(record.value()).contains("MEMBER_ENROLLED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMemberLifecycle_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishMemberLifecycle(
                        "tnt-1", "mbr-1", "suspended", "OPERATOR", null, "grp-1", "scheme-1"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.users.member-lifecycle");
                    assertThat(record.key()).isEqualTo("mbr-1");
                    assertThat(record.value()).contains("MEMBER_STATUS_CHANGED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishMemberLifecycle_envelopeCarriesTenantAndReason() {
        // Guards against arg-position drift on the 7-arg signature. Tightens
        // the assertion beyond "topic + event name" so a swap of tenantId
        // and reason (or a dropped arg) fails loudly instead of quietly
        // publishing garbage into the topic.
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishMemberLifecycle(
                        "tnt-42", "mbr-9", "suspended", "ARREARS_ESCALATION",
                        "2026-07-01", "grp-2", "scheme-2"))
                .verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.value())
                            .contains("\"tenantId\":\"tnt-42\"")
                            .contains("\"memberId\":\"mbr-9\"")
                            .contains("\"status\":\"suspended\"")
                            .contains("\"reason\":\"ARREARS_ESCALATION\"")
                            .contains("\"terminationDate\":\"2026-07-01\"")
                            .contains("\"groupId\":\"grp-2\"")
                            .contains("\"schemeId\":\"scheme-2\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishGroupLifecycle_sendsCorrectTopicWithTenantAndReason() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishGroupLifecycle(
                        "tnt-1", "grp-1", "deactivated", "ARREARS_ESCALATION"))
                .verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.users.group-lifecycle");
                    assertThat(record.key()).isEqualTo("grp-1");
                    assertThat(record.value())
                            .contains("\"event\":\"GROUP_STATUS_CHANGED\"")
                            .contains("\"tenantId\":\"tnt-1\"")
                            .contains("\"groupId\":\"grp-1\"")
                            .contains("\"status\":\"deactivated\"")
                            .contains("\"reason\":\"ARREARS_ESCALATION\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishGroupLifecycle_nullReason_serializesAsEmptyString() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishGroupLifecycle(
                        "tnt-1", "grp-1", "active", null))
                .verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.value()).contains("\"reason\":\"\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishProviderOnboarded_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishProviderOnboarded("prov-1", "City Hospital"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.users.provider-onboarded");
                    assertThat(record.key()).isEqualTo("prov-1");
                    assertThat(record.value()).contains("PROVIDER_ONBOARDED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishRoleAssigned_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(userEventPublisher.publishRoleAssigned("usr-1", "role-1", "admin"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.users.role-assigned");
                    assertThat(record.key()).isEqualTo("usr-1");
                    assertThat(record.value()).contains("ROLE_ASSIGNED");
                })
                .verifyComplete();
    }
}
