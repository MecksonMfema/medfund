package com.medfund.contributions.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.CommitBillingRequest;
import com.medfund.contributions.service.BillingService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.scheduler.JobType;
import com.medfund.shared.scheduler.ResultfulJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Background executor for ad-hoc billing commits triggered from the wizard.
 *
 * <p>Settings JSON shape (written by the enqueue endpoint):
 * <pre>
 * {
 *   "periodStart": "2026-06-01",
 *   "periodEnd":   "2026-06-30",
 *   "groupIds":    [...],         // optional
 *   "memberIds":   [...],         // optional
 *   "actorId":     "uuid",        // who clicked Commit
 *   "actorEmail":  "user@example.com"
 * }
 * </pre>
 * Honours the {@code billing_cycle_config.commit_cooldown_hours} — when the
 * cooldown is active {@link BillingService#commitBilling} throws and the run
 * is marked FAILED with the cooldown message.
 */
@Component
public class BillingCommitExecutor implements ResultfulJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(BillingCommitExecutor.class);

    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    public BillingCommitExecutor(BillingService billingService, ObjectMapper objectMapper) {
        this.billingService = billingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobType getJobType() { return JobType.BILLING_COMMIT; }

    @Override
    public Mono<String> executeAndCapture(String tenantId, String settings) {
        log.info("Running billing commit for tenant: {}", tenantId);
        CommitBillingRequest req;
        String actorId;
        String actorEmail;
        try {
            JsonNode node = objectMapper.readTree(settings);
            LocalDate start = LocalDate.parse(node.get("periodStart").asText());
            LocalDate end = LocalDate.parse(node.get("periodEnd").asText());
            req = new CommitBillingRequest(start, end,
                readUuidArray(node, "groupIds"),
                readUuidArray(node, "memberIds"),
                node.has("insuranceLine") && !node.get("insuranceLine").isNull()
                    ? node.get("insuranceLine").asText() : null);
            actorId = node.has("actorId") && !node.get("actorId").isNull()
                ? node.get("actorId").asText() : AuditActor.SYSTEM_ID;
            actorEmail = node.has("actorEmail") && !node.get("actorEmail").isNull()
                ? node.get("actorEmail").asText() : AuditActor.SYSTEM_EMAIL;
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException(
                "Invalid billing commit settings: " + e.getMessage(), e));
        }
        return billingService.commitBilling(req, actorId, actorEmail)
            .map(this::serialize);
    }

    private static List<UUID> readUuidArray(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        JsonNode arr = node.get(field);
        if (!arr.isArray() || arr.isEmpty()) return null;
        List<UUID> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add(UUID.fromString(n.asText())));
        return out;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise commit result: {}", e.getMessage());
            return "{}";
        }
    }
}
