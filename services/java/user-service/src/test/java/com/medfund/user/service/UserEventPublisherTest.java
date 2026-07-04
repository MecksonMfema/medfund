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
                        "mbr-1", "MBR-123", "grp-1", "scheme-1", "2026-07-01"))
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
                        "mbr-1", "suspended", null, "grp-1", "scheme-1"))
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
