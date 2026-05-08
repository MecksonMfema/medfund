package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTenantEmailTemplateRequest(
        @NotBlank @Size(max = 255) String subject,
        @NotBlank String htmlBody,
        String textBody,
        Boolean enabled
) {}
