package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpsertEmailCampaignRequest(
        UUID senderId,
        @NotBlank @Size(max = 255) String subject,
        @NotBlank String bodyHtml,
        String bodyText,
        /** JSON object literal: { memberStatus?, schemeIds?, groupIds?, enrolledAfter? } */
        String audienceFilter
) {}
