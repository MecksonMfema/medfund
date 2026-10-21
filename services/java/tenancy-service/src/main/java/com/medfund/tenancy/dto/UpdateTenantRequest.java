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

        /** "INDEPENDENT" or "SHARED_WITH_SUFFIX" — see V120 plus
         *  MemberNumberService. Default INDEPENDENT; DB CHECK enforces. */
        String memberNumberScheme,

        /** Regulator jurisdiction code (V131). Free-form string chosen from a
         *  fixed dropdown in the tenant-admin form; blank / null unsets. Gates
         *  regulator-templated reports (Phase 16 of the financial-reporting suite). */
        @Size(max = 40)
        String jurisdictionCode,

        String settings,

        String branding
) {}
