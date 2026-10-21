package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DRAFT-time payload for a commission adjustment. {@code justification} must
 * be at least 20 characters (DB constraint {@code ca_justification_len_ck});
 * enforced early at the service layer for a friendlier 400.
 *
 * <p>{@code adjustmentAmount} is signed — positive credits the producer,
 * negative reverses. Zero is rejected (no-op adjustment adds noise to the
 * ledger). Currency is inferred from the target commission transaction so
 * the drafter never picks a currency that doesn't match the target row.
 */
public record CreateAdjustmentRequest(
        @NotNull UUID targetCommissionTransactionId,
        @NotBlank
        @Pattern(regexp = "^(EX_GRATIA|VOID|MANUAL_CLAWBACK|MANUAL_REVERSAL)$",
                 message = "adjustmentType must be one of EX_GRATIA, VOID, MANUAL_CLAWBACK, MANUAL_REVERSAL")
        String adjustmentType,
        @NotNull BigDecimal adjustmentAmount,
        @NotBlank
        @Size(min = 20, message = "justification must be at least 20 characters")
        String justification
) {}
