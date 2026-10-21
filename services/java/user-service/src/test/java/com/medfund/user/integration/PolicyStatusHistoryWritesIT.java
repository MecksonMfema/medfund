package com.medfund.user.integration;

import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.entity.LifePolicy;
import com.medfund.user.status.LifePolicyStatusTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §A per L2: the policy pathway's SQL seam —
 * {@code PolicyStatusTransitionService.transition} must land a
 * {@code policy_status_history} row in the same transaction as the entity
 * update, validate per-line reason vocab, and treat same-status repeats as
 * no-ops. (The member-side twin of this suite is MemberStatusTransitionIT.)
 */
class PolicyStatusHistoryWritesIT extends AbstractPolicyLifecycleIT {

    @Autowired private LifePolicyStatusTransitionService lifeService;

    @Test
    @WithTenant(TENANT_ID)
    void lapse_writesSingleHistoryRowAndUpdatesPolicy() {
        UUID memberId = seedMember("IT-PL-1", "active", null);
        UUID policyId = seedLifePolicy("LP-IT-" + memberId.toString().substring(0, 8),
                memberId, "active");

        LifePolicy saved = block(lifeService.transition(policyId, "lapse",
                PolicyReasonCode.NON_PAYMENT, "premium overdue 90 days",
                ACTOR_ID, ACTOR_EMAIL));

        assertThat(saved.getStatus()).isEqualTo("lapsed");

        Long count = db.sql(
                        "SELECT COUNT(*)::bigint AS n FROM policy_status_history WHERE policy_id = :id")
                .bind("id", policyId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).as("exactly one history row for one transition").isEqualTo(1L);

        Map<String, String> row = db.sql("""
                SELECT from_status AS fs, to_status AS ts, reason_code AS rc,
                       reason_note AS rn, actor_email AS ae, policy_source AS src
                  FROM policy_status_history WHERE policy_id = :id
                """)
                .bind("id", policyId)
                .map((r, meta) -> Map.of(
                        "from_status", r.get("fs", String.class),
                        "to_status", r.get("ts", String.class),
                        "reason_code", r.get("rc", String.class),
                        "policy_source", r.get("src", String.class),
                        "actor_email", r.get("ae", String.class)))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(row)
                .containsEntry("from_status", "active")
                .containsEntry("to_status", "lapsed")
                .containsEntry("reason_code", "NON_PAYMENT")
                .containsEntry("policy_source", "LIFE_POLICY")
                .containsEntry("actor_email", ACTOR_EMAIL);
    }

    @Test
    @WithTenant(TENANT_ID)
    void repeatTransition_sameStatus_isIdempotent_noSecondHistoryRow() {
        UUID memberId = seedMember("IT-PL-2", "active", null);
        UUID policyId = seedLifePolicy("LP-IT-" + memberId.toString().substring(0, 8),
                memberId, "active");

        block(lifeService.transition(policyId, "lapse", PolicyReasonCode.NON_PAYMENT,
                null, ACTOR_ID, ACTOR_EMAIL));

        LifePolicy replayed = block(lifeService.transition(policyId, "lapse",
                PolicyReasonCode.NON_PAYMENT, null, ACTOR_ID, ACTOR_EMAIL));

        assertThat(replayed.getStatus()).isEqualTo("lapsed");
        Long count = db.sql(
                        "SELECT COUNT(*)::bigint AS n FROM policy_status_history WHERE policy_id = :id")
                .bind("id", policyId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void crossLineVocab_rejected400_withoutTouchingTheRow() {
        UUID memberId = seedMember("IT-PL-3", "active", null);
        UUID policyId = seedLifePolicy("LP-IT-" + memberId.toString().substring(0, 8),
                memberId, "active");

        // TRIP_CANCELLED is travel-only — a life policy must reject it with
        // 400 before any DB write.
        StepVerifier.create(lifeService.transition(policyId, "suspend",
                        PolicyReasonCode.TRIP_CANCELLED, null, ACTOR_ID, ACTOR_EMAIL)
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(e -> {
                    var rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("LIFE_POLICY");
                })
                .verify(Duration.ofSeconds(15));

        String status = db.sql("SELECT status FROM life_policies WHERE id = :id")
                .bind("id", policyId)
                .map((r, meta) -> r.get("status", String.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(status).isEqualTo("active");
        Long historyCount = db.sql(
                        "SELECT COUNT(*)::bigint AS n FROM policy_status_history WHERE policy_id = :id")
                .bind("id", policyId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(historyCount).isZero();
    }
}
