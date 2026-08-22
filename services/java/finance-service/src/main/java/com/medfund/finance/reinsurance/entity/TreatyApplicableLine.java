package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * The set of insurance lines a treaty covers (V085). Composite key on
 * {@code treatyId + insuranceLine}; no surrogate ID. Auto-cession
 * consumers filter treaties on this table so a treaty covering only
 * HEALTH never fires for a LIFE claim.
 */
@Getter
@Setter
@Table("treaty_applicable_line")
public class TreatyApplicableLine {

    @Column("treaty_id")
    private UUID treatyId;

    @Column("insurance_line")
    private String insuranceLine;
}
