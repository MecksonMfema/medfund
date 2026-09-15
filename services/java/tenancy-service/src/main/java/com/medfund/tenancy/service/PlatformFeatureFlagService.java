package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.flags.PlatformFlag;
import com.medfund.tenancy.dto.PlatformFeatureFlagResponse;
import com.medfund.tenancy.entity.PlatformFeatureFlag;
import com.medfund.tenancy.repository.PlatformFeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFeatureFlagService {

    private final PlatformFeatureFlagRepository repo;
    private final AuditPublisher auditPublisher;

    public Flux<PlatformFeatureFlagResponse> list() {
        return repo.findAllByOrderByKeyAsc()
                .mapNotNull(row -> {
                    PlatformFlag meta = safeEnum(row.getKey());
                    if (meta == null) {
                        log.warn("DB row references unknown flag key: {} (enum drift?)", row.getKey());
                        return null;
                    }
                    return PlatformFeatureFlagResponse.from(row, meta);
                });
    }

    public Mono<PlatformFeatureFlagResponse> get(String key) {
        PlatformFlag meta = safeEnum(key);
        if (meta == null) {
            return Mono.error(new IllegalArgumentException("Unknown feature flag: " + key));
        }
        return repo.findById(key)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Flag row missing for " + key + "; PlatformFlagSeeder did not run?")))
                .map(row -> PlatformFeatureFlagResponse.from(row, meta));
    }

    public Mono<PlatformFeatureFlagResponse> update(String key, boolean enabled,
                                                    String actorId, String actorEmail) {
        PlatformFlag meta = safeEnum(key);
        if (meta == null) {
            return Mono.error(new IllegalArgumentException("Unknown feature flag: " + key));
        }
        return repo.findById(key)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Flag row missing for " + key + "; PlatformFlagSeeder did not run?")))
                .flatMap(row -> {
                    boolean before = Boolean.TRUE.equals(row.getEnabled());
                    row.setEnabled(enabled);
                    row.setUpdatedAt(OffsetDateTime.now());
                    row.setUpdatedBy(actorEmail != null ? actorEmail : actorId);
                    row.setVersion((row.getVersion() != null ? row.getVersion() : 0L) + 1L);
                    return repo.save(row)
                            .flatMap(saved -> emitAudit(saved.getKey(), before, enabled, actorId, actorEmail)
                                    .thenReturn(PlatformFeatureFlagResponse.from(saved, meta)));
                });
    }

    private Mono<Void> emitAudit(String key, boolean oldEnabled, boolean newEnabled,
                                 String actorId, String actorEmail) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("enabled", oldEnabled);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", newEnabled);
        AuditEvent event = AuditEvent.create(
                null,
                "PLATFORM_FEATURE_FLAG",
                key,
                key,
                "UPDATE",
                actorId,
                actorEmail,
                before,
                after,
                new String[]{"enabled"},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static PlatformFlag safeEnum(String key) {
        try { return PlatformFlag.valueOf(key); }
        catch (IllegalArgumentException e) { return null; }
    }
}
