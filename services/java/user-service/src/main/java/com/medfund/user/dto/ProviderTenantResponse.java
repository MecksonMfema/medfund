package com.medfund.user.dto;

import com.medfund.user.entity.ProviderTenant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape for a {@code public.provider_tenants} row.
 *
 * <p>Dates render as ISO-8601 strings rather than {@code LocalDate} so the
 * Angular admin modal can bind them without a date adapter. The contract
 * fields ({@code creditLimit}, {@code tariffAgreementId}, the effective
 * dates) have no editor in v1 but ship on the response so an operator can
 * see what a membership carries before unlinking it.
 */
public record ProviderTenantResponse(
        UUID providerId,
        UUID tenantId,
        String status,
        String networkTier,
        Boolean inNetwork,
        String contractEffectiveFrom,
        String contractEffectiveTo,
        BigDecimal creditLimit,
        String creditLimitCurrency,
        UUID tariffAgreementId) {

    public static ProviderTenantResponse from(ProviderTenant pt) {
        return new ProviderTenantResponse(
                pt.getProviderId(),
                pt.getTenantId(),
                pt.getStatus(),
                pt.getNetworkTier(),
                pt.getInNetwork(),
                iso(pt.getContractEffectiveFrom()),
                iso(pt.getContractEffectiveTo()),
                pt.getCreditLimit(),
                pt.getCreditLimitCurrency(),
                pt.getTariffAgreementId());
    }

    private static String iso(LocalDate date) {
        return date != null ? date.toString() : null;
    }
}
