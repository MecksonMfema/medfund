package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.UpdatePlatformSettingsRequest;
import com.medfund.tenancy.entity.PlatformSettings;
import com.medfund.tenancy.repository.PlatformSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read + mutate the singleton platform settings row. Every mutation emits an
 * audit event with the super-admin's identity carried through by the
 * controller layer, then best-effort syncs the branding onto the Keycloak
 * platform realm so the login screen matches. A Keycloak failure is logged
 * and swallowed: the row is already committed and the audit already emitted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private static final Set<String> ALLOWED_LOGO_MIMES =
            Set.of("image/svg+xml", "image/png", "image/jpeg");
    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024; // 2 MB

    private final PlatformSettingsRepository repo;
    private final AuditPublisher auditPublisher;
    private final KeycloakRealmService keycloakRealmService;

    public Mono<PlatformSettings> get() {
        return repo.findFirstBySingletonIsTrue()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Platform settings row missing; V183 migration not applied?")));
    }

    public Mono<PlatformSettings> update(UpdatePlatformSettingsRequest req, String actorId, String actorEmail) {
        return get().flatMap(existing -> {
            Map<String, Object> before = snapshot(existing);
            applyPatch(existing, req);
            stampActor(existing, actorEmail, actorId);
            return repo.save(existing)
                    .flatMap(saved -> emitAudit(before, snapshot(saved), saved.getId(), actorId, actorEmail)
                            .then(keycloakRealmService.updatePlatformRealmBranding(saved))
                            .thenReturn(saved));
        });
    }

    /**
     * Consume a multipart FilePart, validate mime + size, persist bytes on
     * the singleton row, and emit an audit event. Returns the public URL of
     * the logo endpoint (query-cache-busted with the new updatedAt). Syncs the
     * realm too, so a new logo reaches the login screen without a second save.
     */
    public Mono<String> uploadLogo(FilePart file, String actorId, String actorEmail) {
        String mime = file.headers().getContentType() != null
                ? file.headers().getContentType().toString()
                : null;
        if (mime == null || !ALLOWED_LOGO_MIMES.contains(mime)) {
            return Mono.error(new IllegalArgumentException(
                    "Unsupported logo mime type: " + mime + ". Allowed: " + ALLOWED_LOGO_MIMES));
        }

        return DataBufferUtils.join(file.content(), (int) MAX_LOGO_BYTES + 1)
                .flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    if (bytes.length > MAX_LOGO_BYTES) {
                        return Mono.error(new IllegalArgumentException(
                                "Logo exceeds 2MB (got " + bytes.length + " bytes)"));
                    }
                    return get().flatMap(existing -> {
                        Map<String, Object> before = snapshot(existing);
                        existing.setLogoBytes(bytes);
                        existing.setLogoMime(mime);
                        stampActor(existing, actorEmail, actorId);
                        return repo.save(existing)
                                .flatMap(saved -> emitAudit(before, snapshot(saved), saved.getId(), actorId, actorEmail)
                                        .then(keycloakRealmService.updatePlatformRealmBranding(saved))
                                        .thenReturn(saved.logoPath()));
                    });
                });
    }

    public Mono<LogoBytes> loadLogo() {
        return get().flatMap(s -> {
            if (s.getLogoBytes() == null || s.getLogoBytes().length == 0) {
                return Mono.empty();
            }
            return Mono.just(new LogoBytes(s.getLogoBytes(), s.getLogoMime()));
        });
    }

    private void applyPatch(PlatformSettings existing, UpdatePlatformSettingsRequest req) {
        if (req.platformName()     != null) existing.setPlatformName(req.platformName());
        if (req.supportEmail()     != null) existing.setSupportEmail(req.supportEmail());
        if (req.themeTemplateId()  != null) existing.setThemeTemplateId(req.themeTemplateId());
        if (req.darkMode()         != null) existing.setDarkMode(req.darkMode());
        if (req.heroTitle()        != null) existing.setHeroTitle(req.heroTitle());
        if (req.heroSubtitle()     != null) existing.setHeroSubtitle(req.heroSubtitle());
    }

    private void stampActor(PlatformSettings s, String actorEmail, String actorId) {
        s.setUpdatedAt(OffsetDateTime.now());
        s.setUpdatedBy(actorEmail != null ? actorEmail : actorId);
        s.setVersion((s.getVersion() != null ? s.getVersion() : 0L) + 1L);
    }

    /**
     * Snapshot of the row for audit diffing. Excludes raw bytes (audit events
     * are stored in Postgres; a 2 MB blob in every event row would balloon
     * the audit table). {@code logoPresent} flag tells the audit reader
     * whether a logo is set without shipping the payload.
     */
    private Map<String, Object> snapshot(PlatformSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platformName", s.getPlatformName());
        m.put("supportEmail", s.getSupportEmail());
        m.put("themeTemplateId", s.getThemeTemplateId());
        m.put("darkMode", s.getDarkMode());
        m.put("heroTitle", s.getHeroTitle());
        m.put("heroSubtitle", s.getHeroSubtitle());
        m.put("logoMime", s.getLogoMime());
        m.put("logoPresent", s.getLogoBytes() != null && s.getLogoBytes().length > 0);
        m.put("version", s.getVersion());
        return m;
    }

    private Mono<Void> emitAudit(Map<String, Object> before, Map<String, Object> after,
                                 UUID id, String actorId, String actorEmail) {
        AuditEvent event = AuditEvent.create(
                null,
                "PLATFORM_SETTINGS",
                id.toString(),
                "Platform settings",
                "UPDATE",
                actorId,
                actorEmail,
                before,
                after,
                changedFields(before, after),
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    /**
     * Fields whose value actually moved between the two snapshots. Rule 8
     * requires the changed-field list on every mutation event; {@code version}
     * is excluded because it increments on every save and would otherwise
     * appear in every diff.
     */
    private String[] changedFields(Map<String, Object> before, Map<String, Object> after) {
        return after.keySet().stream()
                .filter(key -> !"version".equals(key))
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .toArray(String[]::new);
    }

    public record LogoBytes(byte[] bytes, String mime) {}
}
