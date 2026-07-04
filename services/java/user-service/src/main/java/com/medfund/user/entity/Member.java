package com.medfund.user.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("members")
public class Member {

    @Id
    private UUID id;

    @Column("member_number")
    private String memberNumber;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("date_of_birth")
    private LocalDate dateOfBirth;

    private String gender;

    @Column("national_id")
    private String nationalId;

    private String email;

    private String phone;

    private String address;

    @Column("group_id")
    private UUID groupId;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("keycloak_user_id")
    private String keycloakUserId;

    private String status;

    @Column("enrollment_date")
    private LocalDate enrollmentDate;

    @Column("termination_date")
    private LocalDate terminationDate;

    /**
     * Future-dated status trio (V042). When {@link #scheduledStatus} and
     * {@link #scheduledStatusEffectiveFrom} are both non-null, the
     * SCHEDULED_STATUS_ROLL daily job flips {@link #status} to
     * {@code scheduledStatus} on that date and clears the trio.
     * {@link #scheduledStatusReason} rides on the resulting
     * MEMBER_STATUS_CHANGED lifecycle event so downstream consumers
     * (arrears audit, receipt) can distinguish OPERATOR from
     * ARREARS_ESCALATION.
     */
    @Column("scheduled_status")
    private String scheduledStatus;

    @Column("scheduled_status_effective_from")
    private LocalDate scheduledStatusEffectiveFrom;

    @Column("scheduled_status_reason")
    private String scheduledStatusReason;

    /**
     * Canonical age bucket — derived from {@link #dateOfBirth} at enrolment
     * and maintained as the member crosses age-band boundaries. References
     * {@code age_groups(id)} on the member's scheme. Source of truth for
     * "how many adults / children / seniors" reports — never overwritten
     * by a pricing flag.
     */
    @Column("age_group_id")
    private UUID ageGroupId;

    /**
     * Optional pricing override. When non-null, billing reads the
     * contribution amount from this band instead of {@link #ageGroupId}.
     * Used for cases like a lifelong-child arrangement on an adult with a
     * disability, or a senior discount granted to an under-65 adult.
     */
    @Column("billing_age_group_id")
    private UUID billingAgeGroupId;

    /** Why the override exists (LIFETIME_CHILD, SENIOR_DISCOUNT, …). */
    @Column("billing_override_reason")
    private String billingOverrideReason;

    /** Billing runs before this date keep using {@link #ageGroupId}. */
    @Column("billing_override_effective_from")
    private LocalDate billingOverrideEffectiveFrom;

    /**
     * Per-member explicit premium amount (V030). When set, takes
     * precedence over the resolved age-group price in the billing run.
     * Currency follows the resolved age-group's currency. Effective
     * from {@link #billingOverrideEffectiveFrom}. Used for data-driven
     * individual pricing without minting custom age_groups per member.
     */
    @Column("billing_override_amount")
    private java.math.BigDecimal billingOverrideAmount;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getMemberNumber() { return memberNumber; }
    public void setMemberNumber(String memberNumber) { this.memberNumber = memberNumber; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getNationalId() { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public String getKeycloakUserId() { return keycloakUserId; }
    public void setKeycloakUserId(String keycloakUserId) { this.keycloakUserId = keycloakUserId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getEnrollmentDate() { return enrollmentDate; }
    public void setEnrollmentDate(LocalDate enrollmentDate) { this.enrollmentDate = enrollmentDate; }

    public LocalDate getTerminationDate() { return terminationDate; }
    public void setTerminationDate(LocalDate terminationDate) { this.terminationDate = terminationDate; }

    public String getScheduledStatus() { return scheduledStatus; }
    public void setScheduledStatus(String scheduledStatus) { this.scheduledStatus = scheduledStatus; }

    public LocalDate getScheduledStatusEffectiveFrom() { return scheduledStatusEffectiveFrom; }
    public void setScheduledStatusEffectiveFrom(LocalDate d) { this.scheduledStatusEffectiveFrom = d; }

    public String getScheduledStatusReason() { return scheduledStatusReason; }
    public void setScheduledStatusReason(String scheduledStatusReason) { this.scheduledStatusReason = scheduledStatusReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }

    public UUID getAgeGroupId() { return ageGroupId; }
    public void setAgeGroupId(UUID ageGroupId) { this.ageGroupId = ageGroupId; }

    public UUID getBillingAgeGroupId() { return billingAgeGroupId; }
    public void setBillingAgeGroupId(UUID billingAgeGroupId) { this.billingAgeGroupId = billingAgeGroupId; }

    public String getBillingOverrideReason() { return billingOverrideReason; }
    public void setBillingOverrideReason(String billingOverrideReason) { this.billingOverrideReason = billingOverrideReason; }

    public LocalDate getBillingOverrideEffectiveFrom() { return billingOverrideEffectiveFrom; }
    public void setBillingOverrideEffectiveFrom(LocalDate billingOverrideEffectiveFrom) { this.billingOverrideEffectiveFrom = billingOverrideEffectiveFrom; }

    public java.math.BigDecimal getBillingOverrideAmount() { return billingOverrideAmount; }
    public void setBillingOverrideAmount(java.math.BigDecimal billingOverrideAmount) { this.billingOverrideAmount = billingOverrideAmount; }
}
