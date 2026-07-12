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
                                             String enrollmentDate,
                                             String dateOfBirth) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "MEMBER_ENROLLED");
        payload.put("memberId", memberId);
        payload.put("memberNumber", memberNumber);
        payload.put("groupId",         groupId         != null ? groupId         : "");
        payload.put("schemeId",        schemeId        != null ? schemeId        : "");
        payload.put("enrollmentDate",  enrollmentDate  != null ? enrollmentDate  : "");
        // V061: consumed by BeneficiaryBenefitSeeder to honor per-benefit
        // min_age / max_age gates without a synchronous user-service lookup.
        payload.put("dateOfBirth",     dateOfBirth     != null ? dateOfBirth     : "");
        return publishEvent("medfund.users.member-enrolled", memberId, payload);
    }

    /**
     * Dependant equivalent of {@link #publishMemberEnrolled} — fires
     * after a dependant is created so the contributions consumer can
     * decide whether the effective date lands in an already-billed
     * period (→ auto-post a {@code LATE_ENROLMENT_CHARGE}).
     *
     * <p>Carries the PARENT MEMBER's groupId + schemeId so the consumer
     * doesn't need to re-fetch — a dependant inherits both.
     * Partitioning key is the parent's memberId (not the dependant's own)
     * so events for the same household stay ordered.
     */
    public Mono<Void> publishDependantEnrolled(String dependantId, String memberNumber,
                                                 String memberId,
                                                 String groupId, String schemeId,
                                                 String enrollmentDate,
                                                 String dateOfBirth) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "DEPENDANT_ENROLLED");
        payload.put("dependantId", dependantId);
        payload.put("memberNumber", memberNumber != null ? memberNumber : "");
        payload.put("memberId", memberId);
        payload.put("groupId",         groupId         != null ? groupId         : "");
        payload.put("schemeId",        schemeId        != null ? schemeId        : "");
        payload.put("enrollmentDate",  enrollmentDate  != null ? enrollmentDate  : "");
        // V061: consumed by BeneficiaryBenefitSeeder to honor per-benefit
        // min_age / max_age gates without a synchronous user-service lookup.
        payload.put("dateOfBirth",     dateOfBirth     != null ? dateOfBirth     : "");
        return publishEvent("medfund.users.dependant-enrolled", memberId, payload);
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

    /**
     * Fires on non-status field changes to a member (group swap,
     * scheme swap, dependant↔member swap). Consumers such as
     * {@code GroupChangedConsumer} in the contributions-service decide
     * whether to post arrears / rebate based on the effective date.
     *
     * <p>Envelope is a single flat map so the wire schema stays
     * uniform with other topics — the {@code changeKind} field carries
     * the discriminator ({@code GROUP_CHANGE}, {@code SWAP_APPLIED}
     * etc.); {@code oldValue} / {@code newValue} hold the affected IDs.
     * Partitioning key is the memberId so ordering per household is
     * preserved.
     *
     * @param backdated {@code true} when effectiveDate lies in the
     *                  past — the consumer's arrears walk is only
     *                  meaningful in that case.
     */
    public Mono<Void> publishMemberChanged(String tenantId, String memberId,
                                            String changeKind,
                                            String oldValue, String newValue,
                                            String effectiveDate,
                                            boolean backdated,
                                            String actorId, String actorEmail) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "MEMBER_CHANGED");
        payload.put("tenantId",       tenantId       != null ? tenantId       : "");
        payload.put("memberId", memberId);
        payload.put("changeKind", changeKind);
        payload.put("oldValue",       oldValue       != null ? oldValue       : "");
        payload.put("newValue",       newValue       != null ? newValue       : "");
        payload.put("effectiveDate",  effectiveDate  != null ? effectiveDate  : "");
        payload.put("backdated", Boolean.toString(backdated));
        payload.put("actorId",        actorId        != null ? actorId        : "");
        payload.put("actorEmail",     actorEmail     != null ? actorEmail     : "");
        return publishEvent("medfund.users.member-changed", memberId, payload);
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
