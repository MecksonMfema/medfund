package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Product-level classification for the claim-detail header and any UI that
 * needs to know how a scheme handles per-member ledgers before rendering.
 *
 * <p>{@code tracksMemberBalances} = false means no per-member
 * {@code beneficiary_benefits} rows exist for this scheme and Stage 3
 * adjudication skips the balance check (pure indemnity product). The
 * per-benefit {@code usageMode} tells the UI whether to render a running-
 * balance progress bar, a one-time used/available chip, or a per-event
 * counter.
 */
public record SchemeProductProfileResponse(
        UUID schemeId,
        String insuranceLine,
        String schemeType,
        Boolean tracksMemberBalances,
        BigDecimal annualMemberCap,
        List<BenefitUsageMode> benefitUsageModes
) {
    public record BenefitUsageMode(UUID benefitId, String name, String benefitType, String usageMode) {
        public static BenefitUsageMode from(SchemeBenefit b) {
            return new BenefitUsageMode(
                    b.getId(),
                    b.getName(),
                    b.getBenefitType(),
                    b.getUsageMode() != null ? b.getUsageMode() : "RUNNING_BALANCE"
            );
        }
    }

    public static SchemeProductProfileResponse from(Scheme scheme, List<SchemeBenefit> benefits) {
        return new SchemeProductProfileResponse(
                scheme.getId(),
                scheme.getInsuranceLine(),
                scheme.getSchemeType(),
                scheme.getTracksMemberBalances() != null ? scheme.getTracksMemberBalances() : Boolean.TRUE,
                scheme.getAnnualMemberCap(),
                benefits.stream().map(BenefitUsageMode::from).toList()
        );
    }
}
