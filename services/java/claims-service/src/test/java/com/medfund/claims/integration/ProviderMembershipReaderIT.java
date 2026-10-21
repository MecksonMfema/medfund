package com.medfund.claims.integration;

import com.medfund.claims.repository.ProviderMembershipReader;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ProviderMembershipReader} against real Postgres: the two
 * existence reads behind {@code ClaimService.validateProviderMembership}, which
 * decides whether a submitted claim is accepted or 422'd.
 *
 * <p>Repository-level rather than through {@code POST /api/v1/claims}: the
 * claims mirror in {@code db/test-migration} carries the report columns, not
 * the full submit-path shape (payee type, attachments, benefit id, audit
 * columns), so an HTTP round trip would be testing the fixture rather than the
 * guard. The 422 messages and the ordering against the per-line MODE rule are
 * covered by {@code ClaimServiceTest}; what only a database can answer is
 * whether the SQL itself selects the right rows, which is what this asserts.
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ProviderMembershipReaderIT extends AbstractClaimsReportIT {

    @Autowired
    private ProviderMembershipReader reader;

    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Test
    void isMember_trueForAContractedProvider() {
        UUID provider = seedProvider("Sunrise Clinic");

        assertTrue(isMember(provider, tenantId()),
                "a provider with an active provider_tenants row is a member");
    }

    @Test
    void isMember_falseWhenTheProviderHasNoMembershipRow() {
        UUID provider = seedUnlinkedProvider("Unlinked Diagnostics");

        assertFalse(isMember(provider, tenantId()),
                "existing in public.providers is not the same as being contracted");
    }

    @Test
    void isMember_falseForAnotherTenantsMembership() {
        UUID provider = seedProvider("Sunrise Clinic");

        assertFalse(isMember(provider, OTHER_TENANT),
                "membership is per tenant; the guard must not leak across tenants");
    }

    @Test
    void isMember_falseWhenTheContractIsNotActive() {
        // pending / suspended / terminated all read as "not a member": each
        // means the tenant should not be accruing new liability here.
        UUID provider = seedProvider("Suspended Surgery");
        setMembershipStatus(provider, "suspended");

        assertFalse(isMember(provider, tenantId()));
    }

    @Test
    void isMember_falseForAProviderThatDoesNotExistAtAll() {
        assertFalse(isMember(UUID.randomUUID(), tenantId()));
    }

    @Test
    void servesLine_matchesOnlyTheTaggedLines() {
        UUID provider = seedProvider("Harare Funeral Services", "FUNERAL");

        assertTrue(servesLine(provider, "FUNERAL"));
        assertFalse(servesLine(provider, "HEALTH"),
                "a FUNERAL-only provider must not take a HEALTH claim");
    }

    @Test
    void servesLine_isCaseInsensitiveOnTheCaller() {
        // The claim's derived line always arrives upper-case, but the reader
        // normalises anyway so a lower-case caller can never silently miss.
        UUID provider = seedProvider("Sunrise Clinic", "HEALTH");

        assertTrue(servesLine(provider, "health"));
    }

    @Test
    void servesLine_falseForAnUntaggedProvider() {
        UUID provider = seedUnlinkedProvider("Untagged Practice");

        assertFalse(servesLine(provider, "HEALTH"));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static UUID tenantId() {
        return UUID.fromString(TenantTestContext.current());
    }

    private boolean isMember(UUID providerId, UUID tenantId) {
        return Boolean.TRUE.equals(reader.isMember(providerId, tenantId)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10)));
    }

    private boolean servesLine(UUID providerId, String line) {
        return Boolean.TRUE.equals(reader.servesLine(providerId, line)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10)));
    }

    private void setMembershipStatus(UUID providerId, String status) {
        db.sql("UPDATE provider_tenants SET status = :status WHERE provider_id = :pid")
                .bind("status", status).bind("pid", providerId)
                .fetch().rowsUpdated().contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }
}
