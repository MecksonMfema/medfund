package com.medfund.shared.testfixtures;

import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canned assertions for every {@code *ReportRetrofitIT} in the reports
 * suite. Four concerns, one class:
 *
 * <ul>
 *   <li>{@link #assert403WhenDisabled} — persists an {@code enabled=false}
 *       row in {@code public.tenant_report_config} and expects the endpoint
 *       to short-circuit with 403.</li>
 *   <li>{@link #assertEnvelopeShape} — asserts the response body carries
 *       {@code reportKey}, {@code reportingCurrency}, a {@code data} block,
 *       a {@code perCurrency} map, an {@code fxRates} map, a
 *       {@code warnings} list, and a {@code generatedAt} timestamp. Guards
 *       the G17 envelope shape from silent regressions.</li>
 *   <li>{@link #assertPerCurrencyReflectsFilteredSet} — asserts the
 *       envelope's {@code perCurrency} map matches an expected aggregate.
 *       Guards the G18 filtered-set semantic against accidental global
 *       totals.</li>
 *   <li>{@link #assertFxRatesBestEffort} — asserts the envelope's
 *       {@code fxRates} map covers what's available and the
 *       {@code warnings} list names the misses. Guards the G28
 *       best-effort semantic.</li>
 * </ul>
 *
 * <p>{@code assertSecurityEventPublished} intentionally left out of this
 * helper — its wiring (Kafka test consumer topic wiring, consumer poll
 * duration) is specific enough to each service's IT harness that a shared
 * helper adds more coupling than value. Every service's Kafka IT
 * scaffolding already exposes a topic-listen helper; each
 * {@code *ReportRetrofitIT} calls that directly and reuses this class for
 * the four HTTP-shape assertions.
 */
public final class ReportRetrofitAssertions {

    private ReportRetrofitAssertions() {}

    /**
     * Persist an {@code enabled=false} row for the tenant+key pair, then
     * hit the endpoint via {@code client} and expect 403 Forbidden. The
     * caller is responsible for clearing the row afterwards if the test
     * runs before other enablement-sensitive scenarios.
     */
    public static void assert403WhenDisabled(WebTestClient client, String path,
                                             ReportKey key, UUID tenantId,
                                             DatabaseClient db) {
        db.sql("""
                INSERT INTO public.tenant_report_config
                       (id, tenant_id, report_key, enabled, updated_at)
                VALUES (gen_random_uuid(), :tid, :key, FALSE, NOW())
                    ON CONFLICT (tenant_id, report_key) DO UPDATE
                       SET enabled = FALSE, updated_at = NOW()
                """)
                .bind("tid", tenantId)
                .bind("key", key.name())
                .fetch().rowsUpdated().block();

        client.get().uri(path).exchange()
                .expectStatus().isForbidden();
    }

    /**
     * Guard the G17 envelope shape from silent regression. Fails if any of
     * {@code reportKey}, {@code reportingCurrency}, {@code perCurrency},
     * {@code fxRates}, {@code warnings}, {@code generatedAt} are missing.
     */
    public static <T> void assertEnvelopeShape(ReportResponse<T> envelope,
                                               ReportKey expectedKey) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.reportKey()).isEqualTo(expectedKey.name());
        assertThat(envelope.reportingCurrency()).isNotBlank();
        assertThat(envelope.data()).isNotNull();
        assertThat(envelope.perCurrency()).isNotNull();
        assertThat(envelope.fxRates()).isNotNull();
        assertThat(envelope.warnings()).isNotNull();
        assertThat(envelope.generatedAt()).isNotNull();
    }

    /**
     * Guard the G18 filtered-set semantic. Fails if the envelope's
     * {@code perCurrency} map doesn't equal {@code expected}. Use to lock
     * down that adding a filter narrows the aggregate rather than leaving
     * it at the global total.
     */
    public static <T> void assertPerCurrencyReflectsFilteredSet(
            ReportResponse<T> envelope,
            Map<String, PerCurrencyTotal> expected) {
        assertThat(envelope.perCurrency())
                .as("envelope.perCurrency should match filtered-set aggregate (G18)")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    /**
     * Guard the G28 best-effort FX semantic. Asserts that
     * {@code fxRates} contains a numeric rate for every currency in
     * {@code expectedPresent}, and that {@code warnings} names each
     * currency in {@code expectedMissing} at least once (via a substring
     * check on the currency code so the warning template stays free to
     * evolve).
     */
    public static <T> void assertFxRatesBestEffort(
            ReportResponse<T> envelope,
            java.util.Set<String> expectedPresent,
            java.util.Set<String> expectedMissing) {
        Map<String, BigDecimal> rates = envelope.fxRates();
        List<String> warnings = envelope.warnings();

        assertThat(rates.keySet())
                .as("envelope.fxRates should include every present-rate currency")
                .containsAll(expectedPresent);
        for (String missing : expectedMissing) {
            assertThat(rates)
                    .as("envelope.fxRates should NOT include the missing currency %s (G28)", missing)
                    .doesNotContainKey(missing);
            assertThat(warnings)
                    .as("envelope.warnings should name the missing FX rate for %s (G28)", missing)
                    .anyMatch(w -> w != null && w.contains(missing));
        }
    }
}
