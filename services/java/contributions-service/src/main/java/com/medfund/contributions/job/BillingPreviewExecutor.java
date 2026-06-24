package com.medfund.contributions.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.PreviewBillingRequest;
import com.medfund.contributions.service.BillingService;
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
 * Background executor for ad-hoc billing previews triggered from the wizard.
 *
 * <p>Settings JSON shape (written by the enqueue endpoint):
 * <pre>
 * {
 *   "periodStart": "2026-06-01",
 *   "periodEnd":   "2026-06-30",
 *   "groupIds":    ["uuid", ...],   // optional
 *   "memberIds":   ["uuid", ...]    // optional
 * }
 * </pre>
 * Returns the {@code BillingPreviewResponse} as a JSON string so the UI can
 * deserialise it from {@code scheduled_job_runs.result_payload}.
 */
@Component
public class BillingPreviewExecutor implements ResultfulJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(BillingPreviewExecutor.class);

    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    public BillingPreviewExecutor(BillingService billingService, ObjectMapper objectMapper) {
        this.billingService = billingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobType getJobType() { return JobType.BILLING_PREVIEW; }

    @Override
    public Mono<String> executeAndCapture(String tenantId, String settings) {
        log.info("Running billing preview for tenant: {}", tenantId);
        PreviewBillingRequest req;
        try {
            req = parse(settings);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException(
                "Invalid billing preview settings: " + e.getMessage(), e));
        }
        return billingService.previewBilling(req)
            .map(this::serialize);
    }

    private PreviewBillingRequest parse(String settings) throws Exception {
        JsonNode node = objectMapper.readTree(settings);
        LocalDate start = LocalDate.parse(node.get("periodStart").asText());
        LocalDate end = LocalDate.parse(node.get("periodEnd").asText());
        return new PreviewBillingRequest(start, end, readUuidArray(node, "groupIds"), readUuidArray(node, "memberIds"));
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
            log.warn("Could not serialise preview result: {}", e.getMessage());
            return "{}";
        }
    }
}
