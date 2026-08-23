package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record AssignMemberRequest(
        @NotNull UUID producerId,
        @NotNull LocalDate effectiveFrom,
        @Size(max = 120) String changeReason
) {}
