package com.medfund.claims.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Adjudicator input for {@link com.medfund.claims.controller.ClaimReserveController#set}.
 * A five-character minimum on {@code reasonNote} forces a scannable audit
 * trail — bare "update" isn't useful to the actuary reconstructing the
 * incurred triangle six quarters later.
 */
public record SetClaimReserveRequest(
        @NotNull @DecimalMin("0.0") BigDecimal reservedAmount,
        @NotBlank @Size(min = 5, max = 500) String reasonNote
) {}
