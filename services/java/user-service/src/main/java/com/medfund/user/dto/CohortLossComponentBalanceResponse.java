package com.medfund.user.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CohortLossComponentBalanceResponse(
        UUID cohortId,
        String currency,
        BigDecimal balance
) {
}
