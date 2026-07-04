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

    /**
     * Enriched with {@code enrollmentDate} + {@code schemeId} so the
     * contributions-service consumer can decide whether the new member's
     * effective date falls in an already-billed period (→ auto-post a
     * LATE_ENROLMENT_CHARGE). Nullable fields serialise as empty strings
     * to keep the Map&lt;String,String&gt; envelope uniform.
     */
    public Mono<Void> publishMemberEnrolled(String memberId, String memberNumber,
                                             String groupId, String schemeId,
                                             String enrollmentDate) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "MEMBER_ENROLLED");
        payload.put("memberId", memberId);
        payload.put("memberNumber", memberNumber);
        payload.put("groupId",         groupId         != null ? groupId         : "");
        payload.put("schemeId",        schemeId        != null ? schemeId        : "");
        payload.put("enrollmentDate",  enrollmentDate  != null ? enrollmentDate  : "");
        return publishEvent("medfund.users.member-enrolled", memberId, payload);
    }

    /**
     * Enriched with {@code terminationDate} + {@code groupId} +
     * {@code schemeId} so the contributions-service consumer can figure
     * out whether the termination window overlaps a period that was
     * already billed for this member (→ auto-post a
     * LATE_TERMINATION_CREDIT). All extra fields are optional; empty
     * strings on the wire when null.
     */
    public Mono<Void> publishMemberLifecycle(String tenantId, String memberId, String status,
                                              String reason,
                                              String terminationDate,
                                              String groupId, String schemeId) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "MEMBER_STATUS_CHANGED");
        payload.put("tenantId", tenantId != null ? tenantId : "");
        payload.put("memberId", memberId);
        payload.put("status", status);
        payload.put("reason",          reason          != null ? reason          : "");
        payload.put("terminationDate", terminationDate != null ? terminationDate : "");
        payload.put("groupId",         groupId         != null ? groupId         : "");
        payload.put("schemeId",        schemeId        != null ? schemeId        : "");
        return publishEvent("medfund.users.member-lifecycle", memberId, payload);
    }

    /**
     * Group-level lifecycle event. Fires on every immediate status flip
     * (activate / suspend / terminate / deactivate) so downstream
     * consumers can react to group-scope state changes without polling
     * the members table. The per-member cascade in
     * {@code GroupService.cascadeToMembers} still fires MEMBER_STATUS_CHANGED
     * events per member for row-level notifications.
     */
    public Mono<Void> publishGroupLifecycle(String tenantId, String groupId, String status,
                                              String reason) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "GROUP_STATUS_CHANGED");
        payload.put("tenantId", tenantId != null ? tenantId : "");
        payload.put("groupId", groupId);
        payload.put("status", status);
        payload.put("reason", reason != null ? reason : "");
        return publishEvent("medfund.users.group-lifecycle", groupId, payload);
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
