package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

class MemberEnrolledConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MemberEnrolledConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MemberEnrolledConsumer(null, objectMapper);
    }

    @Test
    void processEvent_validPayload_acksWithoutWrite() {
        String memberId = UUID.randomUUID().toString();
        String groupId = UUID.randomUUID().toString();
        String json = """
            {"event":"MEMBER_ENROLLED","memberId":"%s","memberNumber":"MEM-001","groupId":"%s"}
            """.formatted(memberId, groupId);

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();
    }

    @Test
    void processEvent_invalidJson_returnsError() {
        String json = "not valid json {{{";

        StepVerifier.create(consumer.processEvent(json))
            .verifyError();
    }
}
