package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table("dunning_config")
public class DunningConfig {

    /** Singleton row id — always {@link #SINGLETON_ID}. */
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Column("grace_days")
    private Integer graceDays;

    @Column("suspension_days")
    private Integer suspensionDays;

    /** Renamed from write_off_days in V042 — the deactivation state
     *  is what actually happens; write-off is a downstream finance
     *  side effect. */
    @Column("deactivation_days")
    private Integer deactivationDays;

    @Column("auto_suspend")
    private Boolean autoSuspend;

    @Column("auto_write_off")
    private Boolean autoWriteOff;

    /** V044 — turn the arrears-reminder loop on. */
    @Column("auto_remind")
    private Boolean autoRemind;

    /** V044 — how many days before the SUSPENDED threshold the first
     *  reminder fires. Also gates whether we send reminders in the
     *  "past-suspension → pre-deactivation" window. */
    @Column("reminder_lead_days")
    private Integer reminderLeadDays;

    /** V044 — cadence between reminders. Every {@code reminder_interval_days}
     *  we send a fresh one until the row's arrears clear, the row is
     *  deactivated, or (if disabled below) the row is suspended. */
    @Column("reminder_interval_days")
    private Integer reminderIntervalDays;

    /** V044 — whether reminders continue after suspension. When
     *  FALSE, reminders stop the day the row flips suspended. */
    @Column("reminder_continue_past_suspension")
    private Boolean reminderContinuePastSuspension;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
