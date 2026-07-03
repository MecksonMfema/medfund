package com.medfund.shared.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * The single write API for in-app notifications. Every producer
 * (JobEventPublisher today; InvoiceIssuedListener, PermissionDenied
 * hook, ChatMessageConsumer tomorrow) obtains this component and calls
 * {@link #write(NewNotification)} rather than touching the repository
 * directly. That indirection is what makes the notification channel
 * extensible — new kinds are pure call sites, no wiring changes needed.
 *
 * <p>Writes are best-effort: if the row can't be persisted (broker
 * outage, Postgres blip) the caller's flow continues. In-app
 * notifications are a cosmetic overlay on top of the durable domain
 * events; the domain event is the source of truth, the notification
 * row is the operator's inbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository repository;

    /**
     * Create a notification row. If {@link NewNotification#sourceType()}
     * + {@link NewNotification#sourceId()} are both non-null, the writer
     * first checks for an existing row to keep the operation idempotent
     * against replayed events; otherwise it just inserts.
     *
     * <p>Returns the persisted (or existing) row so callers can chain if
     * they need the id. Errors are logged and swallowed — see the
     * class-level note on best-effort semantics.
     */
    public Mono<Notification> write(NewNotification req) {
        if (req.userId() == null) {
            log.debug("Skipping notification write: userId is null (kind={})", req.kind());
            return Mono.empty();
        }
        Mono<Notification> upsert = req.sourceType() != null && req.sourceId() != null
            ? repository.findBySource(req.userId(), req.sourceType(), req.sourceId())
                    .switchIfEmpty(Mono.defer(() -> insert(req)))
            : insert(req);
        return upsert
            .doOnError(e -> log.warn("Failed to write notification (kind={} user={}): {}",
                req.kind(), req.userId(), e.getMessage()))
            .onErrorResume(e -> Mono.empty());
    }

    private Mono<Notification> insert(NewNotification req) {
        Notification n = new Notification();
        n.setTenantId(req.tenantId());
        n.setUserId(req.userId());
        n.setKind(req.kind());
        n.setTitle(req.title());
        n.setBody(req.body());
        n.setSeverity(req.severity() == null ? NotificationSeverity.INFO : req.severity());
        n.setSourceType(req.sourceType());
        n.setSourceId(req.sourceId());
        n.setActionUrl(req.actionUrl());
        n.setMetadata(req.metadata());
        n.setCreatedAt(Instant.now());
        return repository.save(n);
    }

    /**
     * Value object for producer call sites. Kept as a record so the
     * call site reads as data-in / data-out without a builder tax; a
     * future 15-field growth spurt can migrate to a builder if needed.
     */
    public record NewNotification(
            UUID tenantId,
            UUID userId,
            String kind,
            String title,
            String body,
            String severity,
            String sourceType,
            UUID sourceId,
            String actionUrl,
            String metadata
    ) {}
}
