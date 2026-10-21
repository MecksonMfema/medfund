package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignReviewTaskRequest(@NotNull UUID assigneeUserId) {}
