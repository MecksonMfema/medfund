package com.medfund.user.integration;

import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.service.GroupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §A per grill note 3: the group cascade reaches the central
 * member-status pathway through {@code MemberService.deactivate} →
 * {@code applyOrScheduleStatus}, so deactivating a group with N members must
 * write one {@code member_status_history} row per flipped member in a single
 * round-trip. Also guards the cascade filter (already-terminated members are
 * left alone, preserving their audit history).
 */
class GroupCascadeWritesHistoryIT extends AbstractPolicyLifecycleIT {

    @Autowired private GroupService groupService;

    @Test
    @WithTenant(TENANT_ID)
    void deactivateGroup_writesOneHistoryRowPerCascadedMember() {
        UUID groupId = seedGroup("Cascade IT Group");
        UUID m1 = seedMember("IT-CAS-1", "active", groupId);
        UUID m2 = seedMember("IT-CAS-2", "active", groupId);
        UUID m3 = seedMember("IT-CAS-3", "active", groupId);
        UUID m4 = seedMember("IT-CAS-4", "active", groupId);
        UUID m5 = seedMember("IT-CAS-5", "active", groupId);
        // Already-terminal rows are excluded by the cascade filter.
        UUID alreadyTerminated = seedMember("IT-CAS-6", "terminated", groupId);

        block(groupService.deactivate(groupId, null, "PLANNED", ACTOR_ID, ACTOR_EMAIL));

        Long historyCount = db.sql("""
                SELECT COUNT(*)::bigint AS n FROM member_status_history
                 WHERE to_status = 'deactivated'
                """)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(historyCount)
                .as("N=5 active members → exactly 5 history rows")
                .isEqualTo(5L);

        Integer distinctShapes = db.sql("""
                SELECT COUNT(DISTINCT (member_id, from_status, to_status, reason_code))::bigint AS n
                  FROM member_status_history
                 WHERE to_status = 'deactivated'
                """)
                .map((r, meta) -> r.get("n", Integer.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(distinctShapes).isEqualTo(5);

        String groupStatus = db.sql("SELECT status FROM groups WHERE id = :id")
                .bind("id", groupId)
                .map((r, meta) -> r.get("status", String.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(groupStatus).isEqualTo("deactivated");

        // Every cascaded member actually flipped — including via the pathway,
        // so suspend_reason carries the cascade reason too.
        Long deactivatedMembers = db.sql(
                        "SELECT COUNT(*)::bigint AS n FROM members WHERE group_id = :gid AND status = 'deactivated'")
                .bind("gid", groupId)
                .map((r, meta) -> r.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(deactivatedMembers).isEqualTo(5L);
        String terminatedStatus = db.sql("SELECT status FROM members WHERE id = :id")
                .bind("id", alreadyTerminated)
                .map((r, meta) -> r.get("status", String.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(terminatedStatus).isEqualTo("terminated");

        String reasonCode = db.sql("""
                SELECT DISTINCT reason_code FROM member_status_history
                 WHERE member_id = :id AND to_status = 'deactivated'
                """)
                .bind("id", m1)
                .map((r, meta) -> r.get("reason_code", String.class))
                .one()
                .block(Duration.ofSeconds(10));
        assertThat(reasonCode)
                .as("derived default attribution for operator-initiated deactivation")
                .isEqualTo("admin_deactivate");
    }
}
