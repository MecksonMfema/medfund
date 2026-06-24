package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.EmailTemplateDescriptor;
import com.medfund.tenancy.dto.TenantEmailTemplateResponse;
import com.medfund.tenancy.dto.UpdateTenantEmailTemplateRequest;
import com.medfund.tenancy.entity.TenantEmailTemplate;
import com.medfund.tenancy.repository.TenantEmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the platform-shipped email-template catalogue and the per-tenant
 * override layer. Notification-service (Go) reads this same table at send time
 * to merge tenant overrides on top of the platform defaults — when a tenant
 * has no row for a given key, or the row is disabled, the platform default
 * fires.
 *
 * <p>The catalogue is intentionally a static list rather than a database
 * table: adding a new template is a code change (notification-service must
 * also know about it to fire), and shipping it as code keeps the catalogue
 * and the senders in lock-step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantEmailTemplateService {

    private final TenantEmailTemplateRepository repository;
    private final AuditPublisher auditPublisher;

    /**
     * Canonical list of templates the platform supports. Order is the order
     * tenants see in the Settings UI. New templates appended here become
     * available to every tenant on next reload — no migration required.
     */
    public static final List<EmailTemplateDescriptor> CATALOGUE = List.of(
            new EmailTemplateDescriptor(
                    "WELCOME_EMAIL", "Welcome",
                    "Sent to new members the first time they sign in.",
                    "Welcome to {{tenant.name}}",
                    "<p>Hi {{user.firstName}},</p><p>Welcome to {{tenant.name}}. Your account is ready.</p>",
                    "Hi {{user.firstName}}, welcome to {{tenant.name}}. Your account is ready."),
            new EmailTemplateDescriptor(
                    "INVITE_USER", "Staff invitation",
                    "Sent when a tenant admin invites a new staff member.",
                    "You have been invited to {{tenant.name}}",
                    "<p>You have been invited to join {{tenant.name}} as {{user.role}}. " +
                            "Click <a href=\"{{inviteUrl}}\">here</a> to set up your account. " +
                            "This invitation expires in 7 days.</p>",
                    "Set up your {{tenant.name}} account at {{inviteUrl}} (expires in 7 days)."),
            new EmailTemplateDescriptor(
                    "PASSWORD_RESET", "Password reset",
                    "Sent when a user requests a password reset.",
                    "Reset your {{tenant.name}} password",
                    "<p>We received a request to reset your password. " +
                            "Click <a href=\"{{resetUrl}}\">here</a> to choose a new one. " +
                            "If you didn't ask for this, you can ignore this email.</p>",
                    "Reset your password at {{resetUrl}}. Ignore if you didn't request this."),
            new EmailTemplateDescriptor(
                    "MFA_OTP", "Multi-factor OTP",
                    "Sent when a user requests an email-based one-time code.",
                    "Your {{tenant.name}} sign-in code",
                    "<p>Your one-time code is <strong>{{otp}}</strong>. It expires in 5 minutes.</p>",
                    "Your {{tenant.name}} code is {{otp}} (expires in 5 minutes)."),
            new EmailTemplateDescriptor(
                    "CLAIM_SUBMITTED", "Claim submitted",
                    "Sent to the member when a claim is submitted on their behalf.",
                    "Claim {{claim.number}} received",
                    "<p>We've received your claim {{claim.number}} for {{claim.amount}}. " +
                            "We'll let you know once it's adjudicated.</p>",
                    "Claim {{claim.number}} for {{claim.amount}} received. We'll update you on the outcome."),
            new EmailTemplateDescriptor(
                    "CLAIM_APPROVED", "Claim approved",
                    "Sent to the member when a claim has been approved.",
                    "Claim {{claim.number}} approved",
                    "<p>Good news — claim {{claim.number}} has been approved for {{claim.approvedAmount}}.</p>",
                    "Claim {{claim.number}} approved for {{claim.approvedAmount}}."),
            new EmailTemplateDescriptor(
                    "CLAIM_REJECTED", "Claim rejected",
                    "Sent to the member when a claim has been rejected.",
                    "Claim {{claim.number}} could not be approved",
                    "<p>We were unable to approve claim {{claim.number}}. Reason: {{claim.rejectionReason}}.</p>",
                    "Claim {{claim.number}} could not be approved. Reason: {{claim.rejectionReason}}."),
            new EmailTemplateDescriptor(
                    "PAYMENT_RECEIVED", "Payment received",
                    "Sent when a contribution payment has been recorded.",
                    "Payment of {{payment.amount}} received",
                    "<p>We've received your payment of {{payment.amount}} for invoice {{invoice.number}}. " +
                            "Thank you.</p>",
                    "Payment of {{payment.amount}} for invoice {{invoice.number}} received. Thank you.")
    );

    private static Map<String, EmailTemplateDescriptor> catalogueByKey() {
        Map<String, EmailTemplateDescriptor> map = new LinkedHashMap<>();
        for (EmailTemplateDescriptor d : CATALOGUE) map.put(d.key(), d);
        return map;
    }

    /**
     * Return every template in the catalogue, with the tenant's override merged
     * in where one exists. Order matches {@link #CATALOGUE}.
     */
    public Flux<TenantEmailTemplateResponse> listForTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .collectMap(TenantEmailTemplate::getTemplateKey)
                .flatMapMany(overrides ->
                        Flux.fromIterable(CATALOGUE).map(d -> {
                            TenantEmailTemplate t = overrides.get(d.key());
                            return t != null
                                    ? TenantEmailTemplateResponse.overridden(d, t)
                                    : TenantEmailTemplateResponse.defaultsOnly(d);
                        }));
    }

    /**
     * Create or update a tenant's override for a single template key. The
     * key must exist in {@link #CATALOGUE} — unknown keys reject with an
     * IllegalArgumentException so a typo doesn't silently store dead data.
     */
    @Transactional
    public Mono<TenantEmailTemplateResponse> upsert(UUID tenantId, String key,
                                                    UpdateTenantEmailTemplateRequest req,
                                                    String actorId, String actorEmail) {
        EmailTemplateDescriptor descriptor = catalogueByKey().get(key);
        if (descriptor == null) {
            return Mono.error(new IllegalArgumentException("Unknown email template key: " + key));
        }

        return repository.findByTenantIdAndTemplateKey(tenantId, key)
                .flatMap(existing -> {
                    Map<String, Object> oldMap = entityToMap(existing);
                    existing.setSubject(req.subject());
                    existing.setHtmlBody(req.htmlBody());
                    existing.setTextBody(req.textBody());
                    existing.setEnabled(req.enabled() != null ? req.enabled() : true);
                    existing.setVersion((existing.getVersion() != null ? existing.getVersion() : 1) + 1);
                    existing.setUpdatedBy(parseUuid(actorId));
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(tenantId, saved, oldMap, "UPDATE", actorId, actorEmail)
                                    .thenReturn(TenantEmailTemplateResponse.overridden(descriptor, saved)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    TenantEmailTemplate fresh = new TenantEmailTemplate();
                    fresh.setTenantId(tenantId);
                    fresh.setTemplateKey(key);
                    fresh.setSubject(req.subject());
                    fresh.setHtmlBody(req.htmlBody());
                    fresh.setTextBody(req.textBody());
                    fresh.setEnabled(req.enabled() != null ? req.enabled() : true);
                    fresh.setVersion(1);
                    fresh.setCreatedBy(parseUuid(actorId));
                    fresh.setUpdatedBy(parseUuid(actorId));
                    fresh.setCreatedAt(Instant.now());
                    fresh.setUpdatedAt(Instant.now());
                    return repository.save(fresh)
                            .flatMap(saved -> publishAudit(tenantId, saved, null, "CREATE", actorId, actorEmail)
                                    .thenReturn(TenantEmailTemplateResponse.overridden(descriptor, saved)));
                }));
    }

    /**
     * Drop a tenant's override for a template — subsequent sends fall back to
     * the platform default. No-op if the row doesn't exist (idempotent).
     */
    @Transactional
    public Mono<Void> reset(UUID tenantId, String key, String actorId, String actorEmail) {
        return repository.findByTenantIdAndTemplateKey(tenantId, key)
                .flatMap(existing -> repository.delete(existing)
                        .then(publishAudit(tenantId, existing, entityToMap(existing), "DELETE", actorId, actorEmail)))
                .then();
    }

    private Mono<Void> publishAudit(UUID tenantId, TenantEmailTemplate t, Map<String, Object> oldMap,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> newMap = "DELETE".equals(action) ? null : entityToMap(t);
        var event = AuditEvent.create(
                tenantId.toString(),
                "TENANT_EMAIL_TEMPLATE",
                t.getId().toString(),
                t.getTemplateKey(),
                action,
                actorId,
                actorEmail,
                oldMap,
                newMap,
                null,
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static Map<String, Object> entityToMap(TenantEmailTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateKey", t.getTemplateKey());
        m.put("subject", t.getSubject());
        m.put("enabled", t.getEnabled());
        m.put("version", t.getVersion());
        return m;
    }

    private static UUID parseUuid(String v) {
        try { return v != null ? UUID.fromString(v) : null; } catch (Exception e) { return null; }
    }
}
