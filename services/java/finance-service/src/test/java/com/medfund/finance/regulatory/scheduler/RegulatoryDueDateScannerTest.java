package com.medfund.finance.regulatory.scheduler;

import com.medfund.finance.regulatory.kafka.RegulatoryDueDatePublisher;
import com.medfund.shared.report.RegulatoryDueDateApproachingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatoryDueDateScannerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    // 2026-08-30 → Q2 2026 quarter end 2026-06-30, IPEC due-date 2026-07-30
    // → -31 days out. Not on a scanner tier. For the tier-boundary tests
    // we override the clock to land exactly on 7d / 1d / 0d / -1d.
    private static Clock clockOn(LocalDate d) {
        return Clock.fixed(d.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    @Mock
    private DatabaseClient databaseClient;
    @Mock
    private RegulatoryDueDatePublisher publisher;

    @Test
    void dueTiersMapEnumeratesFourBoundaries() {
        assertThat(RegulatoryDueDateScanner.DUE_TIERS)
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                         7L, "DUE_DATE_7D",
                         1L, "DUE_DATE_1D",
                         0L, "DUE_DATE_0D",
                        -1L, "DUE_DATE_OVERDUE"));
    }

    @Test
    void severityLadderMatchesBannerRules() {
        assertThat(RegulatoryDueDateScanner.severityFor(8)).isEqualTo("INFO");
        assertThat(RegulatoryDueDateScanner.severityFor(7)).isEqualTo("AMBER");
        assertThat(RegulatoryDueDateScanner.severityFor(1)).isEqualTo("AMBER");
        assertThat(RegulatoryDueDateScanner.severityFor(0)).isEqualTo("RED");
        assertThat(RegulatoryDueDateScanner.severityFor(-1)).isEqualTo("RED");
    }

    @Test
    void runOnce_publishesForSevenDayBoundary_recordsDedupe() {
        // IPEC quarterly due 30 days after quarter-end. If quarter Q2 2026
        // ended 2026-06-30 → due 2026-07-30. Reference day 2026-07-23
        // yields daysUntilDue = 7 → DUE_DATE_7D tier fires.
        LocalDate referenceDay = LocalDate.of(2026, 7, 23);
        RegulatoryDueDateScanner scanner = spy(new RegulatoryDueDateScanner(
                databaseClient, publisher, clockOn(referenceDay)));

        // One tenant, ZW jurisdiction → IPEC (jurisdiction) + AML/TAX/VAT (country ZW).
        // Stub the DB-hitting package-private helpers on the spy so we exercise the
        // real orchestration logic (tenant fan-out, tier resolution, publish) without
        // a live database.
        doReturn(Mono.just(false)).when(scanner).alreadySent(any(), any(), any());
        doReturn(Mono.empty()).when(scanner).recordSent(any(), any(), any());
        when(publisher.publish(any(RegulatoryDueDateApproachingEvent.class))).thenReturn(Mono.empty());

        DatabaseClient.GenericExecuteSpec listSpec = org.mockito.Mockito.mock(
                DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<RegulatoryDueDateScanner.TenantMeta> rowsFetch =
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("FROM public.tenants")))
                .thenReturn(listSpec);
        when(listSpec.map(any(java.util.function.BiFunction.class))).thenReturn(rowsFetch);
        when(rowsFetch.all()).thenReturn(reactor.core.publisher.Flux.just(
                new RegulatoryDueDateScanner.TenantMeta(TENANT_ID, "ZW_IPEC_SHORT_TERM", "ZW")));

        Long published = scanner.runOnce().block();

        // Applicable keys for ZW_IPEC_SHORT_TERM + country ZW:
        //   IPEC_QUARTERLY_RETURN (QUARTERLY, +30d)
        //   AML_STR                (QUARTERLY, +30d)
        //   TAX_WITHHELD_RETURN    (MONTHLY,   +15d)
        //   VAT_RETURN             (MONTHLY,   +25d)
        // On 2026-07-23:
        //   Q2 2026 ended 2026-06-30, IPEC due 2026-07-30 → 7 days out → publish DUE_DATE_7D ✔
        //   Q2 2026 ended 2026-06-30, AML  due 2026-07-30 → 7 days out → publish DUE_DATE_7D ✔
        //   June 2026 monthly:   TAX due 2026-07-15 → -8 days (not on tier) → skip
        //   June 2026 monthly:   VAT due 2026-07-25 → 2 days   (not on tier) → skip
        assertThat(published).isEqualTo(2L);

        ArgumentCaptor<RegulatoryDueDateApproachingEvent> ev =
                ArgumentCaptor.forClass(RegulatoryDueDateApproachingEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(ev.capture());
        assertThat(ev.getAllValues()).allSatisfy(e -> {
            assertThat(e.eventTier()).isEqualTo("DUE_DATE_7D");
            assertThat(e.severity()).isEqualTo("AMBER");
            assertThat(e.daysUntilDue()).isEqualTo(7);
            assertThat(e.tenantId()).isEqualTo(TENANT_ID);
        });
        assertThat(ev.getAllValues()).extracting(RegulatoryDueDateApproachingEvent::reportKey)
                .containsExactlyInAnyOrder("IPEC_QUARTERLY_RETURN", "AML_STR");
    }

    @Test
    void runOnce_dedupeHit_skipsPublish() {
        LocalDate referenceDay = LocalDate.of(2026, 7, 23);
        RegulatoryDueDateScanner scanner = spy(new RegulatoryDueDateScanner(
                databaseClient, publisher, clockOn(referenceDay)));
        doReturn(Mono.just(true)).when(scanner).alreadySent(any(), any(), any());

        DatabaseClient.GenericExecuteSpec listSpec = org.mockito.Mockito.mock(
                DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<RegulatoryDueDateScanner.TenantMeta> rowsFetch =
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("FROM public.tenants")))
                .thenReturn(listSpec);
        when(listSpec.map(any(java.util.function.BiFunction.class))).thenReturn(rowsFetch);
        when(rowsFetch.all()).thenReturn(reactor.core.publisher.Flux.just(
                new RegulatoryDueDateScanner.TenantMeta(TENANT_ID, "ZW_IPEC_SHORT_TERM", "ZW")));

        Long published = scanner.runOnce().block();

        assertThat(published).isZero();
        verify(publisher, never()).publish(any(RegulatoryDueDateApproachingEvent.class));
        verify(scanner, never()).recordSent(any(), any(), any());
    }

    @Test
    void runOnce_tenantWithNoApplicableReports_emitsNothing() {
        LocalDate referenceDay = LocalDate.of(2026, 7, 23);
        RegulatoryDueDateScanner scanner = spy(new RegulatoryDueDateScanner(
                databaseClient, publisher, clockOn(referenceDay)));

        DatabaseClient.GenericExecuteSpec listSpec = org.mockito.Mockito.mock(
                DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<RegulatoryDueDateScanner.TenantMeta> rowsFetch =
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("FROM public.tenants")))
                .thenReturn(listSpec);
        when(listSpec.map(any(java.util.function.BiFunction.class))).thenReturn(rowsFetch);
        // No jurisdiction, unknown country — matches no applicability rule.
        when(rowsFetch.all()).thenReturn(reactor.core.publisher.Flux.just(
                new RegulatoryDueDateScanner.TenantMeta(TENANT_ID, null, "GB")));

        Long published = scanner.runOnce().block();

        assertThat(published).isZero();
        verify(publisher, never()).publish(any(RegulatoryDueDateApproachingEvent.class));
        verify(scanner, never()).alreadySent(any(), any(), any());
    }
}
