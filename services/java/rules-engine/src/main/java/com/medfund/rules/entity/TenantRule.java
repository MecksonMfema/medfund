package com.medfund.rules.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-scoped rule row. The {@code definition} column holds the structured
 * {@code RuleDefinition} JSON; {@code drlOverride} is optional raw DRL for
 * advanced authors. Enabled rules are loaded into the tenant's KieSession on
 * each reload; disabled rows stay in the table for history and re-enable.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_rules")
public class TenantRule {

    @Id
    private UUID id;

    @Column("tenant_id")    private UUID    tenantId;
    @Column("rule_key")     private String  ruleKey;
    private String  name;
    private String  description;
    private String  category;
    @Column("template_id")  private String  templateId;
    private Integer priority;

    /** Structured JSON consumed by {@code DrlCompiler}. Always populated. */
    private String  definition;

    /** Optional raw DRL. When non-null, bypasses the structured compile path. */
    @Column("drl_override") private String  drlOverride;

    private Boolean enabled;
    private Integer version;

    @CreatedDate      @Column("created_at") private Instant createdAt;
    @LastModifiedDate @Column("updated_at") private Instant updatedAt;
    @Column("created_by") private UUID createdBy;
    @Column("updated_by") private UUID updatedBy;
}
