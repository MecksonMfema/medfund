package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record BillingCommitResponse(
        long contributionsCreated,
        Map<String, BigDecimal> totalsByCurrency,
        Instant committedAt
) {}
