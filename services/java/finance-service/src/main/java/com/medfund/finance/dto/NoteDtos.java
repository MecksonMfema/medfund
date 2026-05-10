package com.medfund.finance.dto;

import com.medfund.finance.entity.CreditNote;
import com.medfund.finance.entity.DebitNote;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class NoteDtos {

    private NoteDtos() {}

    public record CreateNoteRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 3) String currencyCode,
        @Size(max = 255) String reference,
        UUID taskId,
        String notes
    ) {}

    public record NoteResponse(
        UUID id,
        BigDecimal amount,
        String currencyCode,
        String reference,
        UUID taskId,
        String notes,
        Instant createdAt,
        UUID createdBy
    ) {
        public static NoteResponse from(DebitNote n) {
            return new NoteResponse(n.getId(), n.getAmount(), n.getCurrencyCode(),
                n.getReference(), n.getTaskId(), n.getNotes(), n.getCreatedAt(), n.getCreatedBy());
        }
        public static NoteResponse from(CreditNote n) {
            return new NoteResponse(n.getId(), n.getAmount(), n.getCurrencyCode(),
                n.getReference(), n.getTaskId(), n.getNotes(), n.getCreatedAt(), n.getCreatedBy());
        }
    }
}
