package com.medfund.contributions.service.candidate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the status-filter clauses in the HEALTH and DISABILITY resolvers.
 *
 * <p>These SQL predicates are what silently exclude {@code terminated} and
 * {@code deactivated} members from the billing candidate list — a subtle
 * rewrite that added those statuses back, or dropped the group-status
 * guard, would keep billing dead rows. There's no compile-time signal
 * for that; this test captures the SQL string and pins the invariant.
 *
 * <p>Deliberately a string-level assertion. A full IT is overkill for
 * "does the WHERE clause still say what it says"; a Testcontainers path
 * lives higher up (SchemeServiceIT) and covers the join wiring.
 */
@ExtendWith(MockitoExtension.class)
class CandidateResolverStatusFilterTest {

    @Mock DatabaseClient db;

    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sqlCaptor = ArgumentCaptor.forClass(String.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<PersonCandidate> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.all()).thenReturn(Flux.empty());
    }

    @Test
    void healthResolver_billsOnlyActiveOrSuspendedMembers() {
        HealthCandidateResolver resolver = new HealthCandidateResolver(db);
        resolver.resolveCandidates(List.of(), List.of(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "STANDARD")
                .collectList().block();

        verify(db).sql(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("Member row must be filtered to active/suspended — deactivated + terminated rows must NOT bill")
                .contains("m.status IN ('active', 'suspended')");
        // Same guard on dependants — a dependant of an active member can
        // still be terminated on their own and must drop out.
        assertThat(sql).contains("d.status IN ('active', 'suspended')");
        // Group cascade: a deactivated group's members must fall out
        // even if the member's own row is still active.
        assertThat(sql).contains("g.status = 'active'");
        // Explicit exclusion — deactivated / terminated must not appear
        // in the status allowlist under any circumstance. Guards a
        // refactor that "generalises" the filter by adding them back.
        assertThat(sql).doesNotContain("'deactivated'");
        assertThat(sql).doesNotContain("'terminated'");
    }

    @Test
    void healthResolver_enforcesEnrollmentDateGuard() {
        // Members whose enrollment_date is in the future (scheduled enrol
        // via V042) must not be billed for the current period. Their
        // status may be 'enrolled' anyway which is already outside the
        // active/suspended filter, but the enrollment_date guard is
        // load-bearing when the ScheduledStatusExecutor rolls them to
        // active mid-cycle — bill only from their effective date.
        HealthCandidateResolver resolver = new HealthCandidateResolver(db);
        resolver.resolveCandidates(List.of(), List.of(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "STANDARD")
                .collectList().block();

        verify(db).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("m.enrollment_date <= :periodEnd");
    }

    @Test
    void disabilityResolver_appliesSameStatusFilterAsHealth() {
        DisabilityCandidateResolver resolver = new DisabilityCandidateResolver(db);
        resolver.resolveCandidates(List.of(), List.of(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "STANDARD")
                .collectList().block();

        verify(db).sql(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        // Policy row filter (dp.status), member row filter (m.status), and
        // group cascade — all three must exclude deactivated / terminated.
        assertThat(sql).contains("dp.status IN ('active', 'suspended')");
        assertThat(sql).contains("m.status IN ('active', 'suspended')");
        assertThat(sql).contains("g.status = 'active'");
        assertThat(sql).doesNotContain("'deactivated'");
        assertThat(sql).doesNotContain("'terminated'");
    }
}
