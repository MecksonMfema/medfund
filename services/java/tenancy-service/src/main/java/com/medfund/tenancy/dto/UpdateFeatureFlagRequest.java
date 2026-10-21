package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateFeatureFlagRequest(@NotNull Boolean enabled) {}
