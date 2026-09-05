package com.medfund.claims.siu.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * External referral row — records an SIU case handoff to law enforcement,
 * the sector regulator, or internal HR. No automated push to the
 * external body (deferred to Phase 19.5 per parent plan); this row
 * captures the referral event + free-text response notes for audit.
 *
 * <p>See {@code services/java/tenancy-service/.../db/migration/tenant/V173__siu_referral.sql}
 * for schema. Created via {@code POST /api/v1/siu/cases/{caseId}/referrals}
 * (gated on {@code claims:siu:refer}).
 */
@Getter
@Setter
@Table("siu_referral")
public class SiuReferral {

    @Id
    private UUID id;

    @Column("case_id")              private UUID caseId;
    @Column("referral_to")          private String referralTo;
    @Column("referral_reference")   private String referralReference;
    @Column("referred_by")          private UUID referredBy;
    @Column("referred_by_email")    private String referredByEmail;
    // DB defaults to NOW() on INSERT — callers populate explicitly.
    @Column("referred_at")          private OffsetDateTime referredAt;
    @Column("response_received_at") private OffsetDateTime responseReceivedAt;
    @Column("response_notes")       private String responseNotes;
}
