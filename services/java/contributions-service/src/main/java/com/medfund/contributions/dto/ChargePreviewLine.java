package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row on the charge-preview breakdown — one member or one dependant
 * (personType discriminates). Mirrors the columns a contribution
 * statement carries so an operator scanning both surfaces sees the same
 * information: who, what scheme + age band they sit on, what tariff or
 * override drove the price, and the projected amount.
 *
 * <p>{@code isCustomPriced} surfaces when a per-member
 * {@code billing_override_amount} short-circuited the age-group price —
 * the operator sees at a glance which rows are custom vs standard.
 * {@code effectiveFrom} is populated only when a future
 * {@code billing_age_group_id} kicks in on the projected cycle
 * (upgrade/downgrade); the UI can highlight those rows so the operator
 * knows the price is about to change.
 */
public record ChargePreviewLine(
        UUID memberId,
        UUID dependantId,
        String memberNumber,
        String personName,
        String personType,       // 'MEMBER' or 'DEPENDANT'
        UUID schemeId,
        String schemeName,
        UUID groupId,
        String groupName,
        UUID ageGroupId,
        String ageBandName,
        BigDecimal amount,
        String currencyCode,
        boolean isCustomPriced,
        java.time.LocalDate scheduledSchemeChangeFrom
) {}
