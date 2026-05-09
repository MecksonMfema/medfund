package com.medfund.user.dto;

import java.util.List;

public record AudiencePreviewResponse(
        long count,
        List<Sample> sample
) {
    public record Sample(String memberNumber, String firstName, String lastName, String email) {}
}
