package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only mapping of the {@code groups} table created in V001 baseline.
 * Full Group CRUD lives in a separate slice; this entity exists so the
 * Group Charge page can autocomplete by name without depending on that work.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("groups")
public class Group {

    @Id
    private UUID id;

    private String name;

    @Column("registration_number")
    private String registrationNumber;

    private String status;

    @Column("created_at")
    private Instant createdAt;
}
