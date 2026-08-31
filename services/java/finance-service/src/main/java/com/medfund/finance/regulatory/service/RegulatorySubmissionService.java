package com.medfund.finance.regulatory.service;

import com.medfund.finance.regulatory.entity.RegulatorySubmission;
import com.medfund.finance.regulatory.repository.RegulatorySubmissionRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.security.MfaStepUpGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Regulator submission log service (Phase 16 §0 REG12 + REG13). Every
 * successful export in submit mode inserts a row; a second export for the
 * same {@code (tenant, report_key, period)} auto-increments
 * {@code submission_number}, links back via {@code supersedes_id}, and
 * marks the prior row {@code SUPERSEDED} atomically.
 *
 * <p>Every submit call routes through {@link MfaStepUpGuard#requireStepUp(Jwt)}
 * first — the JWT must carry a fresh MFA factor (see the guard's Javadoc).
 * The gate emits 401 with {@code x-mfa-required: true} on failure so the
 * Angular re-auth modal can prompt Keycloak step-up.
 *
 * <p>Every mutation emits an {@link AuditEvent} with a friendly
 * {@code entityName} matching {@code feedback_audit_entity_name}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegulatorySubmissionService {

    private static final String ENTITY_TYPE = "REGULATORY_SUBMISSION";

    private final RegulatorySubmissionRepository repository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final MfaStepUpGuard mfaGuard;

    /**
     * Record a new submission for {@code (tenant, reportKey, period)}. If a
     * prior row exists it is marked SUPERSEDED and the new row's
     * {@code supersedes_id} + {@code submission_number} chain to it. MFA
     * step-up is enforced before any DB write.
     */
    @Transactional
    public Mono<RegulatorySubmission> submit(UUID tenantId,
                                             String reportKey,
                                             LocalDate periodStart,
                                             LocalDate periodEnd,
                                             UUID sourceRunId,
                                             byte[] xlsxBytes,
                                             Jwt jwt,
                                             String actorId,
                                             String actorEmail,
                                             String attestationNote,
                                             String reasonNote) {
        if (tenantId == null || reportKey == null || reportKey.isBlank()
                || periodStart == null || periodEnd == null || sourceRunId == null
                || xlsxBytes == null || xlsxBytes.length == 0) {
            return Mono.error(new IllegalArgumentException(
                    "tenantId, reportKey, period, sourceRunId, xlsxBytes are all required"));
        }
        return mfaGuard.requireStepUp(jwt)
                .then(repository
                        .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                                tenantId, reportKey, periodStart)
                        .next()
                        .flatMap(prior -> {
                            // A prior row exists — mark it SUPERSEDED and remember its id
                            // as the new row's supersedes link.
                            prior.setStatus("SUPERSEDED");
                            return repository.save(prior)
                                    .map(saved -> new PriorRef(saved.getId(), saved.getSubmissionNumber()));
                        })
                        .defaultIfEmpty(PriorRef.none()))
                .flatMap(prior -> insertRow(tenantId, reportKey, periodStart, periodEnd,
                        sourceRunId, xlsxBytes, actorId, actorEmail,
                        attestationNote, reasonNote,
                        prior.number() + 1, prior.id()));
    }

    /** Link back to the previous submission when amending, or the sentinel {@link #none()} for a first submission. */
    private record PriorRef(UUID id, int number) {
        static PriorRef none() { return new PriorRef(null, 0); }
    }

    private Mono<RegulatorySubmission> insertRow(UUID tenantId, String reportKey,
                                                 LocalDate periodStart, LocalDate periodEnd,
                                                 UUID sourceRunId, byte[] xlsxBytes,
                                                 String actorId, String actorEmail,
                                                 String attestationNote, String reasonNote,
                                                 int submissionNumber, UUID supersedesId) {
        RegulatorySubmission row = new RegulatorySubmission();
        row.setTenantId(tenantId);
        row.setReportKey(reportKey);
        row.setPeriodStart(periodStart);
        row.setPeriodEnd(periodEnd);
        row.setSubmissionNumber(submissionNumber);
        row.setSupersedesId(supersedesId);
        row.setSourceRunId(sourceRunId);
        row.setSubmittedAt(OffsetDateTime.now());
        row.setSubmittedByActorId(parseUuid(actorId));
        row.setSubmittedByActorEmail(actorEmail);
        row.setXlsxBytes(xlsxBytes);
        row.setXlsxSizeBytes((long) xlsxBytes.length);
        row.setXlsxContentHash(sha256Hex(xlsxBytes));
        row.setStatus("SUBMITTED");
        row.setAttestationNote(attestationNote);
        row.setReasonNote(reasonNote);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(tenantId, saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
    }

    /** Set the regulator-assigned filing reference on an existing submission. */
    @Transactional
    public Mono<RegulatorySubmission> setFilingRef(UUID tenantId, UUID submissionId, String filingRef,
                                                   String actorId, String actorEmail) {
        return get(tenantId, submissionId)
                .flatMap(existing -> {
                    RegulatorySubmission snapshot = copy(existing);
                    existing.setFilingRef(filingRef);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(tenantId, saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    /** List every submission for a (tenant, reportKey, period), newest submission_number first. */
    public Flux<RegulatorySubmission> list(UUID tenantId, String reportKey, LocalDate periodStart) {
        return repository.findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                tenantId, reportKey, periodStart);
    }

    /** Get one row with Rule-2 cross-tenant guard. */
    public Mono<RegulatorySubmission> get(UUID tenantId, UUID submissionId) {
        return repository.findById(submissionId)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Regulatory submission not found: " + submissionId)))
                .flatMap(row -> {
                    if (!row.getTenantId().equals(tenantId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Regulatory submission does not belong to tenant"));
                    }
                    return Mono.just(row);
                });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Mono<Void> publishAudit(UUID tenantId, RegulatorySubmission current,
                                    RegulatorySubmission previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null && newMap != null
                ? changedFields(oldMap, newMap) : null;
        return auditPublisher.publish(AuditEvent.create(
                tenantId.toString(),
                ENTITY_TYPE,
                current.getId().toString(),
                String.format("%s / %s..%s / #%d",
                        current.getReportKey(),
                        current.getPeriodStart(),
                        current.getPeriodEnd(),
                        current.getSubmissionNumber()),
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                changed,
                UUID.randomUUID().toString()));
    }

    private static Map<String, Object> toMap(RegulatorySubmission row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportKey", row.getReportKey());
        m.put("periodStart", row.getPeriodStart() != null ? row.getPeriodStart().toString() : null);
        m.put("periodEnd", row.getPeriodEnd() != null ? row.getPeriodEnd().toString() : null);
        m.put("submissionNumber", row.getSubmissionNumber());
        m.put("supersedesId", row.getSupersedesId() != null ? row.getSupersedesId().toString() : null);
        m.put("status", row.getStatus());
        m.put("filingRef", row.getFilingRef());
        m.put("attestationNote", row.getAttestationNote());
        m.put("reasonNote", row.getReasonNote());
        m.put("xlsxSizeBytes", row.getXlsxSizeBytes());
        m.put("xlsxContentHash", row.getXlsxContentHash());
        return m;
    }

    private static String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !java.util.Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private RegulatorySubmission copy(RegulatorySubmission src) {
        RegulatorySubmission c = new RegulatorySubmission();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setReportKey(src.getReportKey());
        c.setPeriodStart(src.getPeriodStart());
        c.setPeriodEnd(src.getPeriodEnd());
        c.setSubmissionNumber(src.getSubmissionNumber());
        c.setSupersedesId(src.getSupersedesId());
        c.setSourceRunId(src.getSourceRunId());
        c.setSubmittedAt(src.getSubmittedAt());
        c.setSubmittedByActorId(src.getSubmittedByActorId());
        c.setSubmittedByActorEmail(src.getSubmittedByActorEmail());
        c.setXlsxBytes(src.getXlsxBytes());
        c.setXlsxSizeBytes(src.getXlsxSizeBytes());
        c.setXlsxContentHash(src.getXlsxContentHash());
        c.setFilingRef(src.getFilingRef());
        c.setStatus(src.getStatus());
        c.setAttestationNote(src.getAttestationNote());
        c.setReasonNote(src.getReasonNote());
        c.setCreatedAt(src.getCreatedAt());
        return c;
    }

    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
