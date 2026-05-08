package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.Map;

@Service
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public UserEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publishMemberEnrolled(String memberId, String memberNumber, String groupId) {
        return publishEvent("medfund.users.member-enrolled", memberId, Map.of(
            "event", "MEMBER_ENROLLED",
            "memberId", memberId,
            "memberNumber", memberNumber,
            "groupId", groupId != null ? groupId : ""
        ));
    }

    public Mono<Void> publishMemberLifecycle(String memberId, String status) {
        return publishEvent("medfund.users.member-lifecycle", memberId, Map.of(
            "event", "MEMBER_STATUS_CHANGED",
            "memberId", memberId,
            "status", status
        ));
    }

    public Mono<Void> publishProviderOnboarded(String providerId, String name) {
        return publishEvent("medfund.users.provider-onboarded", providerId, Map.of(
            "event", "PROVIDER_ONBOARDED",
            "providerId", providerId,
            "name", name
        ));
    }

    public Mono<Void> publishRoleAssigned(String userId, String roleId, String roleName) {
        return publishEvent("medfund.users.role-assigned", userId, Map.of(
            "event", "ROLE_ASSIGNED",
            "userId", userId,
            "roleId", roleId,
            "roleName", roleName
        ));
    }

    /**
     * Tells every service to drop cached permissions for one user (or every
     * user in a tenant when {@code userId} is null). Consumed by
     * {@code PermissionInvalidationConsumer} in the shared module.
     *
     * <p>The key is the tenant ID so partitions are stable per tenant —
     * a single tenant's invalidations are never re-ordered relative to each
     * other.
     */
    public Mono<Void> publishPermissionsInvalidated(String tenantId, String userId) {
        Map<String, String> payload = userId != null
                ? Map.of("event", "PERMISSIONS_INVALIDATED", "tenantId", tenantId, "userId", userId)
                : Map.of("event", "PERMISSIONS_INVALIDATED", "tenantId", tenantId);
        return publishEvent("medfund.permissions.invalidated", tenantId, payload);
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, String> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            var record = new ProducerRecord<>(topic, key, json);
            var senderRecord = SenderRecord.create(record, key);
            return kafkaSender.send(Mono.just(senderRecord))
                .doOnError(e -> log.error("Failed to publish event to {}: {}", topic, e.getMessage()))
                .then();
        } catch (Exception e) {
            log.error("Failed to serialize event for {}: {}", topic, e.getMessage());
            return Mono.empty();
        }
    }
}
