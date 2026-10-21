package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;

import java.util.UUID;

public record TreatyApplicableLineResponse(
        UUID treatyId,
        String insuranceLine
) {
    public static TreatyApplicableLineResponse from(TreatyApplicableLine l) {
        return new TreatyApplicableLineResponse(l.getTreatyId(), l.getInsuranceLine());
    }
}
