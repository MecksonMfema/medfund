package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateMemberRequest(
    @Size(max = 100)
    String firstName,

    @Size(max = 100)
    String lastName,

    @Size(max = 10)
    String gender,

    @Size(max = 50)
    String nationalId,

    @Email @Size(max = 255)
    String email,

    @Size(max = 50)
    String phone,

    String address,

    UUID groupId,

    UUID schemeId,

    // ── Individual-pricing override (Phase A / V030) ────────────────
    // Setting billingOverrideAmount makes the billing run use this
    // amount for this member instead of the age-group price, starting
    // from billingOverrideEffectiveFrom. Currency follows the
    // resolved age-group. Reason is for audit/operator context.
    //
    // To clear an override, send null amount; reason +
    // effective_from become moot. The DB check constraint enforces
    // amount > 0 and amount-requires-effective_from.

    @DecimalMin(value = "0.01", message = "Override amount must be positive")
    @Digits(integer = 15, fraction = 4)
    BigDecimal billingOverrideAmount,

    @Size(max = 40)
    String billingOverrideReason,

    LocalDate billingOverrideEffectiveFrom
) {}
