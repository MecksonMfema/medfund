package com.medfund.finance.regulatory.scheduler;

import com.medfund.finance.regulatory.kafka.RegulatoryDueDatePublisher;
import com.medfund.finance.regulatory.service.RegulatoryDueDateService;
import com.medfund.finance.regulatory.service.RegulatoryReportApplicability;
import com.medfund.shared.report.RegulatoryDueDateApproachingEvent;
import com.medfund.shared.report.ReportCadenceCatalog;
import com.medfund.shared.report.ReportKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily cron (default 03:00 UTC) that emits
 * {@link RegulatoryDueDateApproachingEvent} on four tier boundaries per
 * (tenant, Phase-16 report_key):
 * <ul>
 *   <li>{@code daysUntilDue == 7}  → {@code DUE_DATE_7D}   (INFO)</li>
 *   <li>{@code daysUntilDue == 1}  → {@code DUE_DATE_1D}   (AMBER)</li>
 *   <li>{@code daysUntilDue == 0}  → {@code DUE_DATE_0D}   (AMBER)</li>
 *   <li>{@code daysUntilDue == -1} → {@code DUE_DATE_OVERDUE} (RED)</li>
 * </ul>
 *
 * <p>Applicability comes from {@link RegulatoryReportApplicability} —
 * jurisdiction-gated (IPEC / CMS / NAIC / PMB) and country-gated (AML /
 * TAX / VAT) subsets so ZW-only tenants never receive US-NAIC events.
 * The dedupe table {@code public.regulatory_due_date_notification_sent}
 * blocks duplicate publishes within 24 hours per {@code (tenant_id,
 * report_key, event_tier)} — the scanner is idempotent under repeated
 * fires on the same day.
 *
 * <p>Deploy-order per F-REG7: the Go consumer
 * ({@code services/go/notification-service/internal/regulatory/dispatcher.go})
 * must be running before this cron enables — otherwise events would be
 * emitted into an empty consumer group and picked up late. The
 * {@code regulatory.duedate.scanner.enabled} flag defaults to {@code
 * true} for parity with the retention job pattern; production is expected
 * to leave it enabled once the consumer is deployed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegulatoryDueDateScanner {

    /**
     * The daysUntilDue values that trigger a publish. Each maps 1:1 to a
     * tier string persisted on {@code regulatory_due_date_notification_sent}.
     */
    static final Map<Long, String> DUE_TIERS = Map.of(
             7L, "DUE_DATE_7D",
             1L, "DUE_DATE_1D",
             0L, "DUE_DATE_0D",
            -1L, "DUE_DATE_OVERDUE");

    private final DatabaseClient databaseClient;
    private final RegulatoryDueDatePublisher publisher;
    private final Clock clock;

    @Value("${regulatory.duedate.scanner.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${regulatory.duedate.scanner.cron:0 0 3 * * *}")
    public void scan() {
        if (!enabled) {
            log.debug("[reg-due-date] scanner disabled by config — skipping");
            return;
        }
        runOnce()
                .doOnSuccess(published -> log.info("[reg-due-date] scan complete: published={}", published))
                .doOnError(e -> log.error("[reg-due-date] scan failed: {}", e.getMessage(), e))
                .subscribe();
    }

    /** Package-private for tests: returns the number of published events. */
    Mono<Long> runOnce() {
        LocalDate today = LocalDate.now(clock);
        return listTenants()
                .flatMap(t -> processTenant(t, today), /* concurrency */ 4)
                .reduce(0L, Long::sum);
    }

    private Mono<Long> processTenant(TenantMeta tenant, LocalDate today) {
        List<ReportKey> keys = RegulatoryReportApplicability.applicableFor(
                tenant.jurisdictionCode(), tenant.countryCode());
        if (keys.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(keys)
                .flatMap(key -> processKey(tenant.id(), key, today))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> processKey(UUID tenantId, ReportKey key, LocalDate today) {
        Optional<ReportCadenceCatalog.CadenceInfo> info = ReportCadenceCatalog.lookup(key);
        if (info.isEmpty()) return Mono.just(0L);
        RegulatoryDueDateService.Period period = RegulatoryDueDateService.currentPeriodFor(
                info.get().cadence(), today);
        LocalDate dueDate = period.end().plusDays(info.get().daysPostPeriodEnd());
        long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
        String tier = DUE_TIERS.get(daysUntilDue);
        if (tier == null) {
            return Mono.just(0L);
        }
        return alreadySent(tenantId, key.name(), tier)
                .flatMap(sent -> {
                    if (sent) {
                        log.debug("[reg-due-date] dedupe hit tenant={} key={} tier={}",
                                tenantId, key.name(), tier);
                        return Mono.just(0L);
                    }
                    RegulatoryDueDateApproachingEvent event = new RegulatoryDueDateApproachingEvent(
                            RegulatoryDueDateApproachingEvent.CURRENT_SCHEMA_VERSION,
                            tenantId,
                            key.name(),
                            period.start(),
                            period.end(),
                            dueDate,
                            daysUntilDue,
                            severityFor(daysUntilDue),
                            tier,
                            Instant.now(clock));
                    return publisher.publish(event)
                            .then(recordSent(tenantId, key.name(), tier))
                            .thenReturn(1L);
                });
    }

    private Flux<TenantMeta> listTenants() {
        return databaseClient.sql("""
                        SELECT id, jurisdiction_code, country_code
                          FROM public.tenants
                        """)
                .map((row, meta) -> new TenantMeta(
                        row.get("id", UUID.class),
                        row.get("jurisdiction_code", String.class),
                        row.get("country_code", String.class)))
                .all();
    }

    /** True when a publish for this (tenant, key, tier) already landed in the last 24 hours. */
    Mono<Boolean> alreadySent(UUID tenantId, String reportKey, String tier) {
        return databaseClient.sql("""
                        SELECT 1
                          FROM public.regulatory_due_date_notification_sent
                         WHERE tenant_id = :tenantId
                           AND report_key = :reportKey
                           AND event_tier = :tier
                           AND sent_at > NOW() - INTERVAL '24 hours'
                         LIMIT 1
                        """)
                .bind("tenantId", tenantId)
                .bind("reportKey", reportKey)
                .bind("tier", tier)
                .fetch()
                .first()
                .map(m -> true)
                .defaultIfEmpty(false);
    }

    Mono<Void> recordSent(UUID tenantId, String reportKey, String tier) {
        return databaseClient.sql("""
                        INSERT INTO public.regulatory_due_date_notification_sent
                                (tenant_id, report_key, event_tier)
                         VALUES (:tenantId, :reportKey, :tier)
                        """)
                .bind("tenantId", tenantId)
                .bind("reportKey", reportKey)
                .bind("tier", tier)
                .then();
    }

    static String severityFor(long daysUntilDue) {
        if (daysUntilDue <= 0) return "RED";
        if (daysUntilDue <= 7) return "AMBER";
        return "INFO";
    }

    record TenantMeta(UUID id, String jurisdictionCode, String countryCode) {}
}
