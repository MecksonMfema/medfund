package com.medfund.user.integration;

import com.medfund.rules.fact.MemberLifecycleFact;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.service.MemberLifecycleFactBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §A per L3 + grill note 8: rules-engine auto-termination is the
 * ONE write site that historically set {@code member.setStatus(...)} outside
 * the central pathway. This IT proves the retrofit —
 * {@link MemberLifecycleFactBuilder#applyTermination} writes the flip through
 * {@code MemberStatusTransitionService}, landing a history row with
 * {@code reason_code='auto_termination'} and the marker actor email.
 */
class AutoTerminationWritesHistoryIT extends AbstractPolicyLifecycleIT {

    @Autowired private MemberLifecycleFactBuilder factBuilder;
    @Autowired private MemberRepository memberRepository;

    private MemberLifecycleFact terminationRequested() {
        var fact = new MemberLifecycleFact();
        fact.setTerminationRequested(true);
        return fact;
    }

    @Test
    @WithTenant(TENANT_ID)
    void autoTerm_writesHistoryRowWithAutoTerminationReason() {
        UUID memberId = seedMember("IT-TERM-1", "active", null);
        Member member = memberRepository.findById(memberId).block(Duration.ofSeconds(10));

        Member saved = block(factBuilder.applyTermination(member, terminationRequested()));

        assertThat(saved.getStatus()).isEqualTo("terminated");
        assertThat(saved.getTerminationDate()).isEqualTo(LocalDate.now());

        var row = db.sql("""
                SELECT from_status AS fs, to_status AS ts, reason_code AS rc,
                       reason_note AS rn, actor_email AS ae, actor_id AS ai
                  FROM member_status_history WHERE member_id = :id
                """)
                .bind("id", memberId)
                .map((r, meta) -> Map.of(
                        "from_status", r.get("fs", String.class),
                        "to_status", r.get("ts", String.class),
                        "reason_code", r.get("rc", String.class),
                        "actor_email", r.get("ae", String.class)))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(row)
                .containsEntry("from_status", "active")
                .containsEntry("to_status", "terminated")
                .containsEntry("reason_code", "auto_termination")
                .containsEntry("actor_email", "rules-engine@insureflow");

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM member_status_history WHERE member_id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void noTerminationRequested_leavesMemberAndHistoryUntouched() {
        UUID memberId = seedMember("IT-TERM-2", "active", null);
        Member member = memberRepository.findById(memberId).block(Duration.ofSeconds(10));

        var idleFact = new MemberLifecycleFact();
        idleFact.setTerminationRequested(false);

        Member untouched = block(factBuilder.applyTermination(member, idleFact));

        assertThat(untouched.getStatus()).isEqualTo("active");
        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM member_status_history WHERE member_id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).isZero();
    }

    @Test
    @WithTenant(TENANT_ID)
    void alreadyTerminated_isIdempotent_noExtraHistoryRow() {
        UUID memberId = seedMember("IT-TERM-3", "terminated", null);
        Member member = memberRepository.findById(memberId).block(Duration.ofSeconds(10));

        Member result = block(factBuilder.applyTermination(member, terminationRequested()));

        assertThat(result.getStatus()).isEqualTo("terminated");
        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM member_status_history WHERE member_id = :id")
                .bind("id", memberId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(count).isZero();
    }
}
