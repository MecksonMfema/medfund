package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateTenantRequest(
        @Size(max = 200)
        String name,

        @Size(max = 255)
        String domain,

        UUID planId,

        @Email
        String contactEmail,

        String timezone,

        String membershipModel,

        /** "STANDARD" (scheme-default pricing per insurance line, e.g.
         *  age_groups for HEALTH) or "INDIVIDUAL" (honour per-member
         *  billing_override_amount when set). Default STANDARD; the
         *  DB CHECK enforces the enum. */
        String pricingModel,

        String settings,

        String branding
) {}
