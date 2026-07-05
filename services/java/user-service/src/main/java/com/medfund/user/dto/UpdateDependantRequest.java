package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Partial update of an existing dependant. All fields are optional —
 * null means "no change". Used by the inline dependants editor on the
 * Member detail page. Non-null values are still subject to format checks
 * (e.g. {@code gender} must be one of the canonical values), but a null
 * gender or nationalId on an update is fine and leaves the column alone.
 *
 * <p>Individual-pricing override (Phase A / V030): same three fields
 * as Member. {@code billingOverrideAmount} > 0 sets the per-dependant
 * price; {@code billingOverrideEffectiveFrom} gates when it kicks in.
 * Clear an override via the dedicated /clear-billing-override endpoint —
 * sending null amount here leaves the existing override untouched
 * (consistent with the rest of the patch semantics).
 */
public record UpdateDependantRequest(
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    LocalDate dateOfBirth,
    @Pattern(regexp = "^(male|female|other)$",
        message = "gender must be male, female, or other")
    @Size(max = 10) String gender,
    @Size(max = 50) String relationship,
    @Size(max = 50) String nationalId,

    @DecimalMin(value = "0.01", message = "Override amount must be positive")
    @Digits(integer = 15, fraction = 4)
    BigDecimal billingOverrideAmount,

    @Size(max = 40) String billingOverrideReason,

    LocalDate billingOverrideEffectiveFrom,

    /**
     * Manual age-group override. Points the billing lookup at a
     * different age-band on the parent member's scheme (typical use:
     * a dependant with a disability who can't age out — keep them on
     * the child rate). Not gated on the tenant's pricing_model.
     * Shares {@link #billingOverrideEffectiveFrom} as the kick-in date.
     */
    UUID billingAgeGroupId,

    /**
     * Effective date the dependant becomes a beneficiary. Optional; the
     * update path snaps any supplied date to the 1st of its month.
     * Sending null on an update leaves the existing value alone
     * (partial-update semantics — matches every other field on this
     * DTO).
     */
    LocalDate enrollmentDate
) {}
