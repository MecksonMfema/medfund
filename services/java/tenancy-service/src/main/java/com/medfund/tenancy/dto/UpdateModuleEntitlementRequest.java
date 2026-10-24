package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateModuleEntitlementRequest(@NotNull Boolean enabled) {}
