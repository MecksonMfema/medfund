package com.medfund.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/notes}. Payee (providerId /
 * memberId) is required unless {@code noteType='MEMO'} — enforced by
 * {@link com.medfund.finance.service.NoteService#create} rather than
 * bean validation because it's cross-field.
 */
public record CreateNoteRequest(
        @NotBlank String direction,          // DEBIT | CREDIT
        @NotBlank String noteType,           // TAX_WITHHELD | WRITE_OFF | GOODWILL | ...
        UUID providerId,
        UUID memberId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 3) String currencyCode,
        String reason
) {}
