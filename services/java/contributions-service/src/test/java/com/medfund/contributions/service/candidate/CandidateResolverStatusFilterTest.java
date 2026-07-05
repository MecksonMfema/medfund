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
    void healthResolver_billsActiveSuspendedOrFutureEnrolled() {
        // V048: 'enrolled' is included in the allowlist so a
        // future-effective member/dependant appears on the projected
        // statement covering their enrolment date. The enrollment_date
        // guard on the same WHERE still blocks the bill before cover
        // starts — this widening only affects projections that reach
        // into the enrolment period.
        HealthCandidateResolver resolver = new HealthCandidateResolver(db);
        resolver.resolveCandidates(List.of(), List.of(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "STANDARD")
                .collectList().block();

        verify(db).sql(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("Member row must include active/suspended/enrolled — deactivated + terminated rows must NOT bill")
                .contains("m.status IN ('active', 'suspended', 'enrolled')");
        // Dependant filter is broader than member filter (V046 + V048): active,
        // suspended, and future-enrolled dependants bill for the cycle their
        // effective date lands on; a 'deactivated' dependant continues to
        // bill up to and including the cycle that contains its
        // deactivation_effective_date, then drops off.
        assertThat(sql).contains("d.status IN ('active', 'suspended', 'enrolled')");
        assertThat(sql)
                .as("Deactivated dependants must remain billable through their effective date (V046)")
                .contains("d.status = 'deactivated'")
                .contains("d.deactivation_effective_date >= :periodStart");
        // Group cascade: a deactivated group's members must fall out
        // even if the member's own row is still active.
        assertThat(sql).contains("g.status = 'active'");
        // Member-side terminated rows must never bill under any
        // circumstance. Guards a refactor that "generalises" the filter
        // by adding them back to the member allowlist.
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
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("m.enrollment_date <= :periodEnd");
        // V047: dependant candidacy is now gated on the same
        // enrollment_date shape as the member — not on the row's
        // audit-timestamp `created_at`. Guards against a silent revert
        // that would recover the old proxy filter.
        assertThat(sql).contains("d.enrollment_date <= :periodEnd");
        assertThat(sql).doesNotContain("d.created_at::date <= :periodEnd");
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
        // Member filter widened by V048 for future-enrolled coverage on
        // projected statements — same shape as the health resolver.
        assertThat(sql).contains("m.status IN ('active', 'suspended', 'enrolled')");
        assertThat(sql).contains("g.status = 'active'");
        assertThat(sql).doesNotContain("'deactivated'");
        assertThat(sql).doesNotContain("'terminated'");
    }
}
