package com.medfund.user.integration;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.service.MemberService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Phase 5 of the actuarial module: {@link MemberService#recordDeath} routes
 * through the Phase-13 {@link com.medfund.user.status.MemberStatusTransitionService}
 * with {@code reasonCode='member_death'} and {@code newStatus='deceased'},
 * persisting both the death metadata and a {@code member_status_history} row
 * in one transaction. This IT locks in that end-to-end shape against a real
 * Postgres so a refactor that regresses the atomic couple (or the audit
 * emission) fails here rather than in an unrelated downstream consumer.
 */
class MemberRecordDeathIT extends AbstractPolicyLifecycleIT {

    @Autowired private MemberService memberService;

    /** Same mock instance the parent {@code stubKafkaCollaborators} pre-stubs
     *  to return {@code Mono.empty()} — @Autowired resolves to the
     *  parent's @MockBean, not a fresh mock, so we don't fork the Spring
     *  context. */
    @Autowired private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void recordDeath_setsColumns_flipsStatus_writesHistoryRow_emitsAudit() {
        UUID memberId = seedMember("IT-DEATH-1", "active", null);
        LocalDate deathDate = LocalDate.now().minusDays(3);

        Member saved = block(memberService.recordDeath(memberId, deathDate, "I00-I99",
                ACTOR_ID, ACTOR_EMAIL));

        assertThat(saved.getStatus()).isEqualTo("deceased");
        assertThat(saved.getDeathDate()).isEqualTo(deathDate);
        assertThat(saved.getCauseOfDeath()).isEqualTo("I00-I99");

        Map<String, String> historyRow = db.sql("""
                SELECT from_status AS fs, to_status AS ts, reason_code AS rc,
                       actor_email AS ae
                  FROM member_status_history
                 WHERE member_id = :id
                   AND reason_code = 'member_death'
                """)
                .bind("id", memberId)
                .map((r, meta) -> Map.of(
                        "from_status", r.get("fs", String.class),
                        "to_status", r.get("ts", String.class),
                        "reason_code", r.get("rc", String.class),
                        "actor_email", r.get("ae", String.class)))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(historyRow)
                .containsEntry("from_status", "active")
                .containsEntry("to_status", "deceased")
                .containsEntry("reason_code", "member_death")
                .containsEntry("actor_email", ACTOR_EMAIL);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .anySatisfy(evt -> assertThat(evt.action()).isEqualTo("MEMBER_DEATH_RECORDED"));
    }

    @Test
    @WithTenant(TENANT_ID)
    void recordDeath_futureDate_isRejected() {
        UUID memberId = seedMember("IT-DEATH-2", "active", null);

        StepVerifier.create(
                        memberService.recordDeath(memberId, LocalDate.now().plusDays(1), null,
                                        ACTOR_ID, ACTOR_EMAIL)
                                .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("future"))
                .verify(Duration.ofSeconds(10));

        String status = db.sql("SELECT status FROM members WHERE id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("status", String.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(status).as("status must not flip when validation fails").isEqualTo("active");
    }

    @Test
    @WithTenant(TENANT_ID)
    void recordDeath_afterTerminationDate_isRejected() {
        UUID memberId = seedMember("IT-DEATH-3", "terminated", null);
        LocalDate termDate = LocalDate.now().minusDays(30);
        db.sql("UPDATE members SET termination_date = :td WHERE id = :id")
                .bind("td", termDate)
                .bind("id", memberId)
                .then().block(Duration.ofSeconds(10));

        StepVerifier.create(
                        memberService.recordDeath(memberId, LocalDate.now().minusDays(1), null,
                                        ACTOR_ID, ACTOR_EMAIL)
                                .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("termination_date"))
                .verify(Duration.ofSeconds(10));

        Long persistedDeaths = db.sql("""
                SELECT COUNT(*)::bigint AS n
                  FROM members WHERE id = :id AND death_date IS NOT NULL
                """)
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(persistedDeaths)
                .as("death_date must not persist when validation fails")
                .isEqualTo(0L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void recordDeath_nullDate_isRejected() {
        UUID memberId = seedMember("IT-DEATH-4", "active", null);
        StepVerifier.create(
                        memberService.recordDeath(memberId, null, null, ACTOR_ID, ACTOR_EMAIL)
                                .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("required"))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void recordDeath_unknownMember_surfacesNotFound() {
        StepVerifier.create(
                        memberService.recordDeath(UUID.randomUUID(), LocalDate.now(), null,
                                        ACTOR_ID, ACTOR_EMAIL)
                                .contextWrite(TenantTestContext.put()))
                .expectError(MemberNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }
}
