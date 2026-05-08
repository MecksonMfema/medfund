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

    @Column("write_off_days")
    private Integer writeOffDays;

    @Column("auto_suspend")
    private Boolean autoSuspend;

    @Column("auto_write_off")
    private Boolean autoWriteOff;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
