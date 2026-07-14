package com.medfund.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.AudiencePreviewResponse;
import com.medfund.user.dto.EmailCampaignFilterParams;
import com.medfund.user.dto.EmailCampaignRow;
import com.medfund.user.dto.PageResponse;
import com.medfund.user.dto.UpsertEmailCampaignRequest;
import com.medfund.user.entity.EmailCampaign;
import com.medfund.user.repository.EmailCampaignQueryRepository;
import com.medfund.user.repository.EmailCampaignRepository;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-composed email campaigns. Drafts hold subject + body + audience
 * filter; sending flips status, computes the recipient count, and stamps
 * sent_at. The actual SMTP dispatch happens out-of-band — once
 * notification-service grows a real outbound layer, the {@link #send} method
 * can fan out to a Kafka topic instead of just stamping state.
 *
 * <p>Audience preview compiles the JSONB filter to a dynamic SQL WHERE
 * clause against the members table — supports memberStatus, schemeIds,
 * groupIds, and enrolledAfter filters today.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCampaignService {

    private final EmailCampaignRepository repo;
    private final EmailCampaignQueryRepository queryRepo;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final DatabaseClient db;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public Flux<EmailCampaign> findAll() {
        return repo.findAllOrderByCreatedAtDesc();
    }

    /** Server-side paginated campaigns list with joined sender address + display name. */
    public Mono<PageResponse<EmailCampaignRow>> searchPaged(EmailCampaignFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepo.search(params, size, offset)
                .collectList()
                .zipWith(queryRepo.count(params))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<EmailCampaign> findById(UUID id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Campaign not found: " + id)));
    }

    @Transactional
    public Mono<EmailCampaign> create(UpsertEmailCampaignRequest request, String actorId, String actorEmail) {
        var campaign = new EmailCampaign();
        campaign.setSenderId(request.senderId());
        campaign.setSubject(request.subject());
        campaign.setBodyHtml(request.bodyHtml());
        campaign.setBodyText(request.bodyText());
        campaign.setAudienceFilter(Json.of(normalizeAudience(request.audienceFilter())));
        campaign.setStatus("draft");
        Instant now = Instant.now();
        campaign.setCreatedAt(now);
        campaign.setUpdatedAt(now);
        campaign.setCreatedBy(actorId != null ? UUID.fromString(actorId) : null);
        campaign.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
        return r2dbcTemplate.insert(campaign)
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("subject", saved.getSubject(), "status", saved.getStatus())));
    }

    @Transactional
    public Mono<EmailCampaign> update(UUID id, UpsertEmailCampaignRequest request, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            if (!"draft".equalsIgnoreCase(existing.getStatus())) {
                return Mono.error(new IllegalStateException(
                        "Only draft campaigns can be edited (current status: " + existing.getStatus() + ")"));
            }
            existing.setSenderId(request.senderId());
            existing.setSubject(request.subject());
            existing.setBodyHtml(request.bodyHtml());
            existing.setBodyText(request.bodyText());
            existing.setAudienceFilter(Json.of(normalizeAudience(request.audienceFilter())));
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
            return repo.save(existing)
                    .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                            Map.of("subject", saved.getSubject()),
                            Map.of("subject", saved.getSubject())));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
                repo.deleteById(id).then(audit(existing, "DELETE", actorId, actorEmail,
                        Map.of("subject", existing.getSubject()), null)).then());
    }

    @Transactional
    public Mono<EmailCampaign> send(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            if (!"draft".equalsIgnoreCase(existing.getStatus())) {
                return Mono.error(new IllegalStateException(
                        "Only draft campaigns can be sent (current status: " + existing.getStatus() + ")"));
            }
            String filterJson = existing.getAudienceFilter() != null
                    ? existing.getAudienceFilter().asString() : "{}";
            return countAudience(filterJson).flatMap(count -> {
                existing.setStatus("sent");
                existing.setSentAt(Instant.now());
                existing.setRecipientCount(count.intValue());
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
                return repo.save(existing)
                        .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                                Map.of("status", "draft"),
                                Map.of("status", "sent",
                                       "recipientCount", String.valueOf(count),
                                       "sentAt", saved.getSentAt().toString())));
            });
        });
    }

    public Mono<AudiencePreviewResponse> previewAudience(String filterJson) {
        String json = normalizeAudience(filterJson);
        return countAudience(json).flatMap(count ->
                sampleAudience(json, 10).collectList()
                        .map(sample -> new AudiencePreviewResponse(count, sample)));
    }

    // ── Audience query ──────────────────────────────────────────────────

    private Mono<Long> countAudience(String json) {
        var filter = parseFilter(json);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS total FROM members WHERE 1=1");
        appendWhere(sql, filter);
        var spec = db.sql(sql.toString());
        spec = bindFilter(spec, filter);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private Flux<AudiencePreviewResponse.Sample> sampleAudience(String json, int limit) {
        var filter = parseFilter(json);
        StringBuilder sql = new StringBuilder(
                "SELECT member_number, first_name, last_name, email FROM members WHERE 1=1");
        appendWhere(sql, filter);
        sql.append(" ORDER BY created_at DESC LIMIT ").append(limit);
        var spec = db.sql(sql.toString());
        spec = bindFilter(spec, filter);
        return spec.map(row -> new AudiencePreviewResponse.Sample(
                (String) row.get("member_number"),
                (String) row.get("first_name"),
                (String) row.get("last_name"),
                (String) row.get("email"))).all();
    }

    private record AudienceFilter(String memberStatus, List<UUID> schemeIds,
                                   List<UUID> groupIds, LocalDate enrolledAfter) {}

    private AudienceFilter parseFilter(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String status = node.hasNonNull("memberStatus") ? node.get("memberStatus").asText() : null;
            List<UUID> schemes = readUuidList(node, "schemeIds");
            List<UUID> groups = readUuidList(node, "groupIds");
            LocalDate enrolled = node.hasNonNull("enrolledAfter")
                    ? LocalDate.parse(node.get("enrolledAfter").asText()) : null;
            return new AudienceFilter(status, schemes, groups, enrolled);
        } catch (Exception e) {
            log.warn("Invalid audience filter JSON, treating as empty: {}", e.getMessage());
            return new AudienceFilter(null, List.of(), List.of(), null);
        }
    }

    private List<UUID> readUuidList(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isArray()) return List.of();
        List<UUID> out = new ArrayList<>();
        node.get(field).forEach(n -> {
            try { out.add(UUID.fromString(n.asText())); } catch (Exception ignore) {}
        });
        return out;
    }

    private void appendWhere(StringBuilder sql, AudienceFilter f) {
        if (f.memberStatus() != null && !f.memberStatus().isBlank()) {
            sql.append(" AND status = :memberStatus");
        }
        if (!f.schemeIds().isEmpty()) sql.append(" AND scheme_id = ANY(:schemeIds)");
        if (!f.groupIds().isEmpty())  sql.append(" AND group_id  = ANY(:groupIds)");
        if (f.enrolledAfter() != null) sql.append(" AND enrollment_date >= :enrolledAfter");
    }

    private DatabaseClient.GenericExecuteSpec bindFilter(
            DatabaseClient.GenericExecuteSpec spec, AudienceFilter f) {
        if (f.memberStatus() != null && !f.memberStatus().isBlank()) {
            spec = spec.bind("memberStatus", f.memberStatus());
        }
        if (!f.schemeIds().isEmpty()) spec = spec.bind("schemeIds", f.schemeIds().toArray(UUID[]::new));
        if (!f.groupIds().isEmpty())  spec = spec.bind("groupIds",  f.groupIds().toArray(UUID[]::new));
        if (f.enrolledAfter() != null) spec = spec.bind("enrolledAfter", f.enrolledAfter());
        return spec;
    }

    private String normalizeAudience(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            objectMapper.readTree(json);
            return json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private Mono<EmailCampaign> audit(EmailCampaign campaign, String action, String actorId, String actorEmail,
                                       Map<String, Object> oldVal, Map<String, Object> newVal) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "EmailCampaign", campaign.getId().toString(),
                    campaign.getSubject(),
                    action, actorId, actorEmail,
                    oldVal, newVal,
                    new String[]{"subject", "status", "sentAt"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(campaign);
        });
    }
}
