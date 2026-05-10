package com.medfund.shared.scheduler;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("scheduled_job_configs")
public class ScheduledJobConfig {

    @Id
    private UUID id;

    /**
     * Owning tenant. {@code null} for platform-global jobs (e.g. tenant
     * onboarding sweeps). Stamped from the writer's {@link
     * com.medfund.shared.tenant.TenantContext} at create time.
     */
    @Column("tenant_id")
    private UUID tenantId;

    @Column("job_type")
    private String jobType;

    private String name;

    @Column("cron_expression")
    private String cronExpression;

    @Column("is_enabled")
    private Boolean isEnabled;

    private String settings; // JSONB — job-specific config

    @Column("last_executed_at")
    private Instant lastExecutedAt;

    @Column("next_execution_at")
    private Instant nextExecutionAt;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Boolean getIsEnabled() {
        return isEnabled;
    }

    public void setIsEnabled(Boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    public Instant getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(Instant lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
    }

    public Instant getNextExecutionAt() {
        return nextExecutionAt;
    }

    public void setNextExecutionAt(Instant nextExecutionAt) {
        this.nextExecutionAt = nextExecutionAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
