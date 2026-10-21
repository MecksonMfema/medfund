package com.medfund.claims.siu.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only activity log row for an SIU case. Application code never
 * UPDATE or DELETE these rows — every state transition, evidence upload,
 * flag link, and referral emits a new note. Investigator narrative notes
 * carry {@code note_type = COMMENT}.
 *
 * <p>Feeds Phase 10's activity-timeline widget on the case-detail page.
 */
@Getter
@Setter
@Table("siu_case_note")
public class SiuCaseNote {

    @Id
    private UUID id;

    @Column("case_id")       private UUID caseId;
    @Column("author_id")     private UUID authorId;
    @Column("author_email")  private String authorEmail;
    @Column("note_type")     private String noteType;
    @Column("body")          private String body;
    // DB defaults to NOW() on INSERT — application code populates this
    // explicitly before save so the value is visible on the returned entity
    // (R2DBC does not re-fetch DEFAULT-populated columns).
    @Column("created_at")    private OffsetDateTime createdAt;
}
