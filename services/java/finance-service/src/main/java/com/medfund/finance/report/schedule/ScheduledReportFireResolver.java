package com.medfund.finance.report.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Phase 17 §A.2 — resolves which schedules should fire at the current tick.
 * Reads {@code public.tenant_report_schedule} joined with {@code public.tenants}
 * (for {@code timezone}), converts the fire time into each tenant's local TZ,
 * and matches against (cadence, dayOfWeek/dayOfMonth, hourOfDay).
 *
 * <p>The probe fires hourly at HH:05 UTC; matching happens on the local hour,
 * so a schedule set for 08:00 in {@code Africa/Harare} fires at UTC 06:05
 * (winter) — the 5-minute slack after HH accommodates DB + timezone lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReportFireResolver {

    private static final Set<Integer> QUARTER_START_MONTHS = Set.of(1, 4, 7, 10);

    private final DatabaseClient databaseClient;

    public Flux<TenantScheduleFireCandidate> findCandidates(OffsetDateTime firedAt) {
        return databaseClient.sql("""
                        SELECT s.id, s.tenant_id, s.report_key, s.cadence,
                               s.hour_of_day, s.day_of_week, s.day_of_month,
                               s.reporting_currency,
                               s.updated_by_actor_id, s.updated_by_actor_email,
                               t.timezone, t.slug, t.name
                          FROM public.tenant_report_schedule s
                          JOIN public.tenants t ON t.id = s.tenant_id
                         WHERE s.enabled = TRUE
                        """)
                .map((row, meta) -> TenantScheduleFireCandidate.fromRow(row))
                .all()
                .filter(cand -> matchesNow(cand, firedAt));
    }

    /**
     * True when {@code firedAt}, converted to the tenant's TZ, matches the
     * schedule's (cadence, day, hour).
     */
    boolean matchesNow(TenantScheduleFireCandidate cand, OffsetDateTime firedAt) {
        ZoneId zone;
        try {
            zone = ZoneId.of(cand.timezone() != null ? cand.timezone() : "UTC");
        } catch (DateTimeException e) {
            log.warn("[scheduled-report-resolver] invalid tenant.timezone '{}' for tenant {} — schedule {} skipped",
                    cand.timezone(), cand.tenantId(), cand.scheduleId());
            return false;
        }
        if (cand.hourOfDay() == null) return false;

        ZonedDateTime nowLocal = firedAt.atZoneSameInstant(zone);
        int localHour = nowLocal.getHour();
        int localDay = nowLocal.getDayOfMonth();
        int localMonth = nowLocal.getMonthValue();
        DayOfWeek localDow = nowLocal.getDayOfWeek();

        if (localHour != cand.hourOfDay()) return false;

        return switch (cand.cadence() != null ? cand.cadence() : "") {
            case "WEEKLY" -> cand.dayOfWeek() != null && cand.dayOfWeek() == localDow.getValue();
            case "MONTHLY" -> cand.dayOfMonth() != null && cand.dayOfMonth() == localDay;
            case "QUARTERLY" -> localDay == 1 && QUARTER_START_MONTHS.contains(localMonth);
            case "ANNUAL" -> localDay == 1 && localMonth == 1;
            default -> false;
        };
    }
}
