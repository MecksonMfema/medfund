package com.medfund.claims.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/claims}. Line-specific optional
 * fields (vehicle registration, incident report, death certificate,
 * etc.) live on the flat record — the service picks the ones the
 * derived insurance line actually needs and rejects a claim that is
 * missing required fields for its line.
 *
 * <p>{@code insuranceLine} is advisory: the authoritative value is
 * resolved server-side from the picked scheme via {@code SchemeClient}.
 * A mismatch is logged, not rejected.
 */
public record SubmitClaimRequest(
        @NotNull UUID memberId,
        UUID dependantId,
        /** Nullable — LIFE, DISABILITY, and (optionally) FUNERAL claims are
         *  paid directly to the member without a provider on file. The
         *  service enforces per-line rules; see
         *  {@code ClaimService.validateProviderPolicy}. */
        UUID providerId,
        @NotNull UUID schemeId,
        UUID benefitId,
        String claimType,
        String insuranceLine,
        /** Optional. When present, the claim is tagged as part of an
         *  operator-defined batch; when blank, the claim stands alone.
         *  The server never generates a batch number — batching is a
         *  purely operator-driven choice per submission. */
        String batchNumber,
        @NotNull LocalDate serviceDate,
        @NotNull @DecimalMin("0.01") BigDecimal claimedAmount,
        @NotBlank String currencyCode,
        String diagnosisCodes,
        String procedureCodes,
        String notes,
        List<ClaimLineRequest> lines,

        // Line-specific optional fields — presence enforced per line
        // by ClaimService.validateLineRequirements().
        String vehicleRegistration,
        String incidentLocation,
        String incidentReportRef,
        String policeReportRef,
        String propertyAddress,
        String deathCertificateRef,
        String deceasedRelationship,
        String travelDestination,
        LocalDate travelStartDate,
        LocalDate travelEndDate,
        String disabilityAssessmentRef,
        String lifeCertificateRef,

        /** Optional list of documents attached at capture time. Metadata
         *  only — byte upload is wired separately. */
        List<ClaimAttachment> attachments
) {
    public SubmitClaimRequest {
        if (claimType == null || claimType.isBlank()) {
            claimType = "medical";
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = "USD";
        }
    }
}
