package com.medfund.shared.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * REST surface the Angular bell reads from. All endpoints are scoped
 * to the caller (the JWT subject) — there is no cross-user access from
 * this controller; super-admin overviews would live under a separate
 * admin route.
 *
 * <p>Mounted from the {@code shared} module so any Java service that
 * embeds shared automatically exposes {@code /api/v1/notifications}.
 * The Angular client hits whichever service the gateway routes there
 * (currently contributions-service).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notifications the bell dropdown reads")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class NotificationsController {

    private final NotificationRepository repository;

    @GetMapping
    @Operation(summary = "Recent notifications for the current user",
        description = "Returns up to `limit` notifications for the caller, most recent first. The bell polls " +
                      "every 30s. Rows are permanent — mark-seen updates the seen_at column but does not delete.")
    public Flux<NotificationSummary> list(
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = parseUuid(jwt);
        if (userId == null) return Flux.empty();
        int capped = Math.max(1, Math.min(limit, 100));
        return repository.findRecentForUser(userId, capped).map(NotificationSummary::from);
    }

    @GetMapping("/unseen-count")
    @Operation(summary = "Unseen notification count for the current user",
        description = "Cheaper than /notifications when the client only needs the badge number.")
    public Mono<UnseenCount> unseenCount(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = parseUuid(jwt);
        if (userId == null) return Mono.just(new UnseenCount(0));
        return repository.countUnseenForUser(userId).map(n -> new UnseenCount(n.intValue()));
    }

    @PostMapping("/mark-all-seen")
    @Operation(summary = "Mark every unseen notification as read",
        description = "Idempotent — running with nothing unseen is a no-op that returns marked=0.")
    public Mono<MarkResult> markAllSeen(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = parseUuid(jwt);
        if (userId == null) return Mono.just(new MarkResult(0));
        return repository.markAllSeenForUser(userId, Instant.now()).map(MarkResult::new);
    }

    @PostMapping("/{id}/mark-seen")
    @Operation(summary = "Mark a single notification as read")
    public Mono<MarkResult> markSeen(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = parseUuid(jwt);
        if (userId == null) return Mono.just(new MarkResult(0));
        return repository.markSeen(id, userId, Instant.now()).map(MarkResult::new);
    }

    private static UUID parseUuid(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) return null;
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public record UnseenCount(int unseen) {}
    public record MarkResult(int marked) {}
}
