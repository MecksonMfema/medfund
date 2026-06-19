package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.UpsertEmailSenderRequest;
import com.medfund.user.entity.EmailSender;
import com.medfund.user.repository.EmailSenderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSenderService {

    private final EmailSenderRepository repo;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<EmailSender> findAll() {
        return repo.findAllOrdered();
    }

    public Mono<EmailSender> findById(UUID id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Email sender not found: " + id)));
    }

    @Transactional
    public Mono<EmailSender> create(UpsertEmailSenderRequest request, String actorId, String actorEmail) {
        var sender = new EmailSender();
        sender.setAddress(request.address());
        sender.setDisplayName(request.displayName());
        sender.setNotes(request.notes());
        sender.setStatus("pending");
        Instant now = Instant.now();
        sender.setCreatedAt(now);
        sender.setUpdatedAt(now);
        sender.setCreatedBy(actorId != null ? UUID.fromString(actorId) : null);
        sender.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);

        return r2dbcTemplate.insert(sender)
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("address", saved.getAddress(), "status", saved.getStatus())));
    }

    @Transactional
    public Mono<EmailSender> update(UUID id, UpsertEmailSenderRequest request, String actorId, String actorEmail) {
        return findById(id)
                .flatMap(existing -> {
                    String prevAddress = existing.getAddress();
                    if (request.address() != null) existing.setAddress(request.address());
                    if (request.displayName() != null) existing.setDisplayName(request.displayName());
                    if (request.notes() != null) existing.setNotes(request.notes());
                    existing.setUpdatedAt(Instant.now());
                    existing.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
                    return repo.save(existing)
                            .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("address", prevAddress),
                                    Map.of("address", saved.getAddress())));
                });
    }

    @Transactional
    public Mono<EmailSender> verify(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            existing.setStatus("verified");
            existing.setVerifiedAt(Instant.now());
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
            return repo.save(existing)
                    .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                            Map.of("status", "pending"),
                            Map.of("status", "verified", "verifiedAt", saved.getVerifiedAt().toString())));
        });
    }

    @Transactional
    public Mono<EmailSender> revoke(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            existing.setStatus("revoked");
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId != null ? UUID.fromString(actorId) : null);
            return repo.save(existing)
                    .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                            Map.of("status", existing.getStatus()),
                            Map.of("status", "revoked")));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing ->
                repo.deleteById(id).then(audit(existing, "DELETE", actorId, actorEmail,
                        Map.of("address", existing.getAddress()), null)).then());
    }

    private Mono<EmailSender> audit(EmailSender sender, String action, String actorId, String actorEmail,
                                     Map<String, Object> oldVal, Map<String, Object> newVal) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "EmailSender", sender.getId().toString(),
                    sender.getAddress(),
                    action, actorId, actorEmail,
                    oldVal, newVal,
                    new String[]{"address", "status", "verifiedAt"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(sender);
        });
    }
}
