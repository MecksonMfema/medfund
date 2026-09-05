package com.medfund.finance.report.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * matchesNow() is pure logic — tested without spinning up any Spring / R2DBC
 * context. The DB query itself is exercised by the integration IT.
 */
class ScheduledReportFireResolverTest {

    private ScheduledReportFireResolver resolver;

    @BeforeEach
    void init() {
        resolver = new ScheduledReportFireResolver(null);
    }

    private TenantScheduleFireCandidate cand(String cadence, int hour,
                                             Integer dow, Integer dom, String tz) {
        return new TenantScheduleFireCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COMMISSION_STATEMENT",
                cadence,
                hour, dow, dom,
                "USD",
                UUID.randomUUID(),
                "admin@acme",
                tz,
                "acme", "Acme Corp");
    }

    @Test
    void monthly_matchesOnDayAndHour_inTenantZone() {
        TenantScheduleFireCandidate c = cand("MONTHLY", 8, null, 1, "Africa/Harare"); // UTC+2
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-01T06:05:00Z"); // 08:05 in Harare
        assertThat(resolver.matchesNow(c, firedAt)).isTrue();
    }

    @Test
    void monthly_missesWhenHourOffByOne() {
        TenantScheduleFireCandidate c = cand("MONTHLY", 8, null, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-01T09:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void weekly_matchesOnDayOfWeekAndHour() {
        // 2026-08-31 is Monday (dayOfWeek=1). Hour 8, TZ UTC.
        TenantScheduleFireCandidate c = cand("WEEKLY", 8, 1, null, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-08-31T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isTrue();
    }

    @Test
    void weekly_missesWhenDayOfWeekWrong() {
        TenantScheduleFireCandidate c = cand("WEEKLY", 8, 2 /* Tue */, null, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-08-31T08:05:00Z"); // Mon
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void quarterly_matchesOnQuarterStartMonthDayOneAndHour() {
        TenantScheduleFireCandidate c = cand("QUARTERLY", 8, null, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-10-01T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isTrue();
    }

    @Test
    void quarterly_missesInNonQuarterMonth() {
        TenantScheduleFireCandidate c = cand("QUARTERLY", 8, null, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-11-01T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void annual_matchesJanuary1() {
        TenantScheduleFireCandidate c = cand("ANNUAL", 8, null, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-01-01T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isTrue();
    }

    @Test
    void annual_missesAnyOtherDay() {
        TenantScheduleFireCandidate c = cand("ANNUAL", 8, null, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-02-01T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void invalidTimezone_isRejectedGracefully() {
        TenantScheduleFireCandidate c = cand("MONTHLY", 8, null, 1, "Not/AValidZone");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-09-01T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void unknownCadence_missesQuietly() {
        TenantScheduleFireCandidate c = cand("FORTNIGHTLY", 8, 1, 1, "UTC");
        OffsetDateTime firedAt = OffsetDateTime.parse("2026-08-31T08:05:00Z");
        assertThat(resolver.matchesNow(c, firedAt)).isFalse();
    }

    @Test
    void nullHourOfDay_misses() {
        TenantScheduleFireCandidate c = new TenantScheduleFireCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "COMMISSION_STATEMENT",
                "MONTHLY", null, null, 1, "USD",
                null, null, "UTC", "acme", "Acme");
        assertThat(resolver.matchesNow(c, OffsetDateTime.parse("2026-09-01T08:05:00Z"))).isFalse();
    }
}
