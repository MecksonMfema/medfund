package com.medfund.user.dto;

import java.util.UUID;

/**
 * A single hit from the unified beneficiary typeahead. Members and
 * dependants share this shape so the frontend can render one flat
 * suggestion list and route the picked item to (memberId, dependantId)
 * on the claim payload via {@link #kind}.
 *
 * <p>For a MEMBER hit, {@code sponsorId} + {@code sponsorName} are
 * {@code null} and {@code id} is the member ID. For a DEPENDANT hit,
 * {@code sponsorId} is the primary member's ID (goes into
 * {@code memberId} on the claim) and {@code id} goes into
 * {@code dependantId}.
 */
public record BeneficiarySearchResult(
        Kind kind,
        UUID id,
        String memberNumber,
        String firstName,
        String lastName,
        UUID sponsorId,
        String sponsorName,
        String sponsorMemberNumber
) {
    public enum Kind { MEMBER, DEPENDANT }

    public static BeneficiarySearchResult ofMember(UUID id, String memberNumber, String firstName, String lastName) {
        return new BeneficiarySearchResult(Kind.MEMBER, id, memberNumber, firstName, lastName,
                null, null, null);
    }

    public static BeneficiarySearchResult ofDependant(UUID id, String memberNumber, String firstName, String lastName,
                                                       UUID sponsorId, String sponsorFirstName, String sponsorLastName,
                                                       String sponsorMemberNumber) {
        String sponsorName = (safe(sponsorFirstName) + " " + safe(sponsorLastName)).trim();
        return new BeneficiarySearchResult(Kind.DEPENDANT, id, memberNumber, firstName, lastName,
                sponsorId, sponsorName.isEmpty() ? null : sponsorName, sponsorMemberNumber);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
