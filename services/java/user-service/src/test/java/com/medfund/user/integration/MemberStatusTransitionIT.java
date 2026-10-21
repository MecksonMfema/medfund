package com.medfund.user.integration;

import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.entity.Member;
import com.medfund.user.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §A per L2 + L3: proves {@code MemberService} transitions land a
 * {@code member_status_history} row in the SAME transaction as the entity
 * update — the seam unit mocks cannot see. Also guards plan invariant #13
 * (same-status idempotency) and the rollback story when the history insert
 * fails (real DROP TABLE failure injection, not a mock).
 */
class MemberStatusTransitionIT extends AbstractPolicyLifecycleIT {

    @Autowired private MemberService memberService;

    @Test
    @WithTenant(TENANT_ID)
    void lapse_immediate_writesSingleHistoryRowAndUpdatesMember() {
        UUID memberId = seedMember("IT-LAPSE-1", "active", null);

        Member saved = block(memberService.lapse(memberId, null, "grace window expired",
                ACTOR_ID, ACTOR_EMAIL));

        assertThat(saved.getStatus()).isEqualTo("lapsed");
        assertThat(saved.getSuspendReason()).isEqualTo("grace window expired");

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM member_status_history WHERE member_id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).as("exactly one history row for one transition").isEqualTo(1L);

        Map<String, String> row = db.sql("""
                SELECT from_status AS fs, to_status AS ts, reason_code AS rc,
                       reason_note AS rn, actor_email AS ae
                  FROM member_status_history WHERE member_id = :id
                """)
                .bind("id", memberId)
                .map((r, meta) -> Map.of(
                        "from_status", r.get("fs", String.class),
                        "to_status", r.get("ts", String.class),
                        "reason_code", r.get("rc", String.class),
                        "reason_note", r.get("rn", String.class),
                        "actor_email", r.get("ae", String.class)))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(row)
                .containsEntry("from_status", "active")
                .containsEntry("to_status", "lapsed")
                .containsEntry("reason_code", "admin_lapse")
                .containsEntry("reason_note", "grace window expired")
                .containsEntry("actor_email", ACTOR_EMAIL);
    }

    @Test
    @WithTenant(TENANT_ID)
    void repeatTransition_sameStatus_isIdempotent_noSecondHistoryRow() {
        UUID memberId = seedMember("IT-LAPSE-2", "active", null);
        block(memberService.lapse(memberId, null, "first", ACTOR_ID, ACTOR_EMAIL));

        // Same-status replay — plan invariant #13: no-op, no extra row.
        Member replayed = block(memberService.lapse(memberId, null, "second", ACTOR_ID, ACTOR_EMAIL));

        assertThat(replayed.getStatus()).isEqualTo("lapsed");
        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM member_status_history WHERE member_id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void recorderFailure_rollsBackEntityUpdate_transactionalGuard() {
        UUID memberId = seedMember("IT-LAPSE-3", "active", null);

        // Real failure injection: the history table is gone, so the INSERT
        // inside the pathway's transaction fails and must drag the entity
        // update back with it. The table is restored in a finally block so a
        // failed assertion can never leak the drop into sibling tests
        // (shared context + container).
        db.sql("DROP TABLE member_status_history").then().block(Duration.ofSeconds(10));
        try {
            StepVerifier.create(
                            memberService.lapse(memberId, null, "boom", ACTOR_ID, ACTOR_EMAIL)
                                    .contextWrite(TenantTestContext.put()))
                    .expectError()
                    .verify(Duration.ofSeconds(15));

            String status = db.sql("SELECT status FROM members WHERE id = :id")
                    .bind("id", memberId)
                    .map((r, meta) -> r.get("status", String.class))
                    .one()
                    .block(Duration.ofSeconds(10));
            assertThat(status)
                    .as("entity save must roll back when the history insert fails")
                    .isEqualTo("active");
        } finally {
            db.sql("""
                    CREATE TABLE member_status_history (
                        id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        member_id        UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
                        from_status      VARCHAR(30),
                        to_status        VARCHAR(30) NOT NULL,
                        effective_at     TIMESTAMPTZ NOT NULL,
                        actor_id         UUID,
                        actor_email      VARCHAR(255),
                        reason_code      VARCHAR(50),
                        reason_note      TEXT,
                        created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN (
                            'initial_backfill', 'backfill_from_termination_date', 'backfill_from_suspend_reason',
                            'admin_activate', 'admin_suspend', 'admin_terminate', 'admin_deactivate', 'admin_lapse',
                            'arrears_lapse', 'arrears_clear', 'scheduled_change', 'group_cascade',
                            'dependant_swap', 'enrolment', 'auto_termination', 'other'
                        ))
                    )
                    """).then().block(Duration.ofSeconds(10));
        }
    }
}
