package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateDependantRequest(
    @NotNull
    UUID memberId,

    @NotBlank @Size(max = 100)
    String firstName,

    @NotBlank @Size(max = 100)
    String lastName,

    @NotNull
    LocalDate dateOfBirth,

    @NotBlank
    @Pattern(regexp = "^(male|female|other)$",
        message = "gender must be male, female, or other")
    @Size(max = 10)
    String gender,

    @NotBlank @Size(max = 50)
    String relationship,

    @NotBlank @Size(max = 50)
    String nationalId,

    /**
     * Optional custom-premium triple. Same INDIVIDUAL-model gating as
     * the parent member's override (V030): honoured only when the
     * tenant's {@code pricing_model = 'INDIVIDUAL'}; frontend gates
     * the section on the tenant model so STANDARD tenants never send
     * these fields. If {@code amount} is present, {@code effectiveFrom}
     * is required — enforced in {@code DependantService.create} with
     * an early 400 rather than a downstream CHECK-constraint failure.
     */
    @DecimalMin(value = "0.01", message = "billingOverrideAmount must be positive")
    BigDecimal billingOverrideAmount,

    @Size(max = 40)
    String billingOverrideReason,

    LocalDate billingOverrideEffectiveFrom,

    /**
     * Manual age-group override. Points the billing lookup at a
     * different age-band on the parent member's scheme. Not gated on
     * pricing_model — every model honours it. Shares
     * {@link #billingOverrideEffectiveFrom} as the kick-in date.
     */
    UUID billingAgeGroupId,

    /**
     * Effective date the dependant becomes a beneficiary (V047). Optional
     * on the request — {@link com.medfund.user.service.DependantService#create}
     * defaults to the 1st of the current month when null. Callers may
     * back-date; the contributions side posts arrears for missed
     * cycles. Always snapped to the 1st of a month by the service.
     */
    LocalDate enrollmentDate
) {
    /** Normalise any submitted date to the 1st of its month, matching
     *  members.enrollment_date's constraint. Called by the service. */
    public LocalDate enrollmentDateOrDefault() {
        LocalDate raw = enrollmentDate != null ? enrollmentDate : LocalDate.now();
        return raw.withDayOfMonth(1);
    }
}
