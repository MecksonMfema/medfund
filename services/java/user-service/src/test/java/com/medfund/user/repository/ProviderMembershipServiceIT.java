package com.medfund.user.repository;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.user.entity.ProviderInsuranceLine;
import com.medfund.user.entity.ProviderTenant;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.exception.TenantNotFoundException;
import com.medfund.user.dto.ProviderResponse;
import com.medfund.user.service.ProviderMembershipService;
import com.medfund.user.service.ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Round-trip IT for {@link ProviderMembershipService} against real Postgres:
 * link, duplicate-link conflict, unlink, line add / remove, and the two
 * not-found paths. Rides {@link AbstractProviderMembershipIT}'s dedicated
 * container so the composite PKs and the V185 CHECK constraints are the real
 * ones rather than a mock's opinion of them.
 *
 * <p>Service-level rather than through {@code WebTestClient}: the HTTP layer
 * adds only path binding and the {@code GlobalExceptionHandler} mapping, and
 * what is worth pinning here is that each exception type the handler keys on
 * is the one actually raised ({@code ProviderNotFoundException} for 404,
 * {@code IllegalStateException} for 409, {@code IllegalArgumentException} for
 * 400), plus that the audit envelope carries readable names.
 */
class ProviderMembershipServiceIT extends AbstractProviderMembershipIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProviderMembershipService service;
    @Autowired private ProviderService providerService;
    @Autowired private ProviderTenantRepository membershipRepository;
    @Autowired private ProviderInsuranceLineRepository lineRepository;
    @Autowired private DatabaseClient db;

    private static final String ACTOR_ID = "8ac1b0ce-0000-4000-8000-00000000a11e";
    private static final String ACTOR_EMAIL = "superadmin@medfund.example";

    private UUID providerId;
    private UUID tenantId;

    @BeforeEach
    void seed() {
        db.sql("TRUNCATE public.provider_tenants, public.provider_insurance_lines, "
                + "public.providers, public.tenants CASCADE").then().block(TIMEOUT);

        providerId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        db.sql("INSERT INTO public.providers (id, name) VALUES (:id, 'Sunrise Clinic')")
                .bind("id", providerId).then().block(TIMEOUT);
        db.sql("INSERT INTO public.tenants (id, name, slug) VALUES (:id, 'Health First Medical', 'health-first')")
                .bind("id", tenantId).then().block(TIMEOUT);
    }

    // ── link / unlink ────────────────────────────────────────────────

    @Test
    void link_createsTheMembershipWithV185Defaults() {
        ProviderTenant saved = link();

        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getNetworkTier()).isEqualTo("STANDARD");
        assertThat(saved.getInNetwork()).isTrue();
        assertThat(saved.getContractEffectiveFrom()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(ACTOR_ID));

        assertThat(membershipRepository.findByProviderIdAndTenantId(providerId, tenantId).block(TIMEOUT))
                .as("the row is really on the table, not just in the returned entity")
                .isNotNull();
    }

    @Test
    void link_emitsAnAuditEventWithReadableNamesAndActorEmail() {
        link();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.entityType()).isEqualTo("PROVIDER_TENANT");
        assertThat(event.action()).isEqualTo("LINK");
        assertThat(event.entityName())
                .as("entityName is friendly text, never the composite UUID key")
                .isEqualTo("Sunrise Clinic @ Health First Medical");
        assertThat(event.actorEmail()).isEqualTo(ACTOR_EMAIL);
        assertThat(event.entityId()).isEqualTo(providerId + "::" + tenantId);
    }

    @Test
    void link_publishesTheBusinessEvent() {
        link();

        verify(userEventPublisher).publishProviderTenantLinked(providerId.toString(), tenantId.toString());
    }

    @Test
    void link_twice_conflicts() {
        link();

        assertThatThrownBy(this::link)
                .isInstanceOf(IllegalStateException.class)     // → 409
                .hasMessageContaining("Sunrise Clinic")
                .hasMessageContaining("Health First Medical");
    }

    @Test
    void link_unknownProvider_notFound() {
        assertThatThrownBy(() -> service.link(UUID.randomUUID(), tenantId, ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    @Test
    void link_unknownTenant_notFound() {
        // Resolved before the INSERT so the caller gets a 404 rather than the
        // FK violation the database would otherwise raise.
        assertThatThrownBy(() -> service.link(providerId, UUID.randomUUID(), ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void unlink_removesTheMembership_andIsIdempotent() {
        link();

        service.unlink(providerId, tenantId, ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);
        assertThat(membershipRepository.findByProviderIdAndTenantId(providerId, tenantId).block(TIMEOUT)).isNull();

        // Second call is a no-op: no audit event, no business event, no error.
        service.unlink(providerId, tenantId, ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);
        verify(userEventPublisher).publishProviderTenantUnlinked(providerId.toString(), tenantId.toString());
    }

    @Test
    void unlink_absentMembership_emitsNothing() {
        service.unlink(providerId, tenantId, ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        verify(auditPublisher, never()).publish(any());
        verify(userEventPublisher, never()).publishProviderTenantUnlinked(any(), any());
    }

    // ── insurance-line tags ──────────────────────────────────────────

    @Test
    void addLine_tagsTheProvider_andListsIt() {
        service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        List<String> lines = service.listLines(providerId)
                .map(ProviderInsuranceLine::getInsuranceLine).collectList().block(TIMEOUT);
        assertThat(lines).containsExactly("HEALTH");
    }

    @Test
    void addLine_normalisesTheUiAliasMotorToVehicle() {
        service.addLine(providerId, "motor", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        assertThat(lineRepository.existsByProviderIdAndLine(providerId, "VEHICLE").block(TIMEOUT))
                .as("MOTOR is the Angular label; VEHICLE is the stored code")
                .isTrue();
    }

    @Test
    void addLine_unknownLine_isRejectedBeforeTheCheckConstraint() {
        assertThatThrownBy(() -> service.addLine(providerId, "WRONG", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)   // → 400
                .hasMessageContaining("Unknown insurance line");

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void addLine_twice_conflicts() {
        service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        assertThatThrownBy(() -> service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)      // → 409
                .hasMessageContaining("already tagged for HEALTH");
    }

    @Test
    void addLine_emitsAnAuditEventNamingTheProviderAndLine() {
        service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.entityType()).isEqualTo("PROVIDER_INSURANCE_LINE");
        assertThat(event.action()).isEqualTo("ADD");
        assertThat(event.entityName()).isEqualTo("Sunrise Clinic (HEALTH)");
        assertThat(event.actorEmail()).isEqualTo(ACTOR_EMAIL);
    }

    @Test
    void removeLine_dropsTheTag_andIsIdempotent() {
        service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        service.removeLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);
        assertThat(lineRepository.existsByProviderIdAndLine(providerId, "HEALTH").block(TIMEOUT)).isFalse();

        service.removeLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);   // no error
    }

    @Test
    void removeLine_unknownProvider_notFound() {
        assertThatThrownBy(() -> service.removeLine(UUID.randomUUID(), "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    // ── list hydration ───────────────────────────────────────────────

    @Test
    void searchPage_carriesEachRowsMembershipsAndLineTags() {
        // The admin console renders both as pill columns; they are batched onto
        // the page rather than fetched per row.
        link();
        service.addLine(providerId, "HEALTH", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);
        service.addLine(providerId, "TRAVEL", ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);

        UUID untouched = UUID.randomUUID();
        db.sql("INSERT INTO public.providers (id, name) VALUES (:id, 'Orphan Practice')")
                .bind("id", untouched).then().block(TIMEOUT);

        List<ProviderResponse> rows = providerService.searchPage(null, null, null, 1, 20)
                .block(TIMEOUT).content();

        ProviderResponse linked = rows.stream().filter(r -> r.id().equals(providerId)).findFirst().orElseThrow();
        assertThat(linked.tenantIds()).containsExactly(tenantId);
        assertThat(linked.insuranceLines()).containsExactly("HEALTH", "TRAVEL");

        ProviderResponse orphan = rows.stream().filter(r -> r.id().equals(untouched)).findFirst().orElseThrow();
        assertThat(orphan.tenantIds()).isEmpty();
        assertThat(orphan.insuranceLines())
                .as("a provider with no tags renders an empty pill list, not null")
                .isEmpty();
    }

    private ProviderTenant link() {
        return service.link(providerId, tenantId, ACTOR_ID, ACTOR_EMAIL).block(TIMEOUT);
    }
}
