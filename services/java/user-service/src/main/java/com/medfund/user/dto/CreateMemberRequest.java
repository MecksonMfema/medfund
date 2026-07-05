package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateMemberRequest(
    @NotBlank @Size(max = 100)
    String firstName,

    @NotBlank @Size(max = 100)
    String lastName,

    @NotNull
    LocalDate dateOfBirth,

    @NotBlank @Pattern(regexp = "^(male|female|other)$",
        message = "gender must be male, female, or other")
    @Size(max = 10)
    String gender,

    @NotBlank @Size(max = 50)
    String nationalId,

    @NotBlank @Email @Size(max = 255)
    String email,

    @Size(max = 50)
    String phone,

    String address,

    UUID groupId,

    /** Scheme assignment is mandatory at enrolment — every member must be on a scheme. */
    @NotNull
    UUID schemeId,

    LocalDate enrollmentDate,

    /**
     * Optional custom-premium triple, honoured only when the tenant's
     * {@code pricing_model = 'INDIVIDUAL'} (V030). Same semantics as the
     * update path — {@code amount != null} requires {@code effectiveFrom}
     * to be set, otherwise MemberService throws a 400 rather than let a
     * SQL CHECK produce a mystery constraint violation. Frontend gates
     * the section on {@code tenant.pricingModel} so tenants on STANDARD
     * never send these fields.
     */
    @DecimalMin(value = "0.01", message = "billingOverrideAmount must be positive")
    BigDecimal billingOverrideAmount,

    @Size(max = 40)
    String billingOverrideReason,

    LocalDate billingOverrideEffectiveFrom
) {
    /**
     * Enrolment dates are always the first of a month. Callers may pass any
     * date within the target month; we normalise to the 1st here so billing
     * cycles and pro-rata calculations have a stable anchor.
     *
     * <p>Operators are allowed to back-date — the form may submit a date
     * in the past, in which case the contribution side must post the
     * arrears adjustment. That financial flow is a follow-up; this method
     * is concerned only with the day-of-month normalisation.
     */
    public LocalDate enrollmentDateOrDefault() {
        LocalDate raw = enrollmentDate != null ? enrollmentDate : LocalDate.now();
        return raw.withDayOfMonth(1);
    }
}
