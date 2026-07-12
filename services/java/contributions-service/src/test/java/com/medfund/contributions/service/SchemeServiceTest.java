package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreateAgeGroupRequest;
import com.medfund.contributions.dto.CreateSchemeBenefitRequest;
import com.medfund.contributions.dto.CreateSchemeRequest;
import com.medfund.contributions.dto.UpdateSchemeRequest;
import com.medfund.contributions.entity.AgeGroup;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.exception.DuplicateSchemeException;
import com.medfund.contributions.exception.SchemeNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemeServiceTest {

    @Mock
    private SchemeRepository schemeRepository;

    @Mock
    private SchemeBenefitRepository schemeBenefitRepository;

    @Mock
    private AgeGroupRepository ageGroupRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private org.springframework.r2dbc.core.DatabaseClient db;
    @Mock
    private org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec dbExec;

    @InjectMocks
    private SchemeService schemeService;

    /**
     * The age-group create/update paths call {@code db.sql(...).bind(...).then()}
     * to insert/version the price-history row. Stub the chain so the
     * mainline tests can ignore the price-history side-effect — its
     * own behaviour is covered by an integration test.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubPriceHistoryDbChain() {
        org.mockito.Mockito.lenient().when(db.sql(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(dbExec);
        org.mockito.Mockito.lenient().when(dbExec.bind(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(dbExec);
        org.mockito.Mockito.lenient().when(dbExec.then()).thenReturn(Mono.empty());
    }

    private final String actorId = UUID.randomUUID().toString();
    private final String actorEmail = "actor@test.example";

    @Test
    void findAll_returnsSchemes() {
        var scheme1 = createTestScheme();
        var scheme2 = createTestScheme();
        scheme2.setName("Silver Plan");

        when(schemeRepository.findAllOrderByName()).thenReturn(Flux.just(scheme1, scheme2));

        StepVerifier.create(schemeService.findAll())
            .expectNext(scheme1)
            .expectNext(scheme2)
            .verifyComplete();

        verify(schemeRepository).findAllOrderByName();
    }

    @Test
    void findById_existing_returnsScheme() {
        var scheme = createTestScheme();

        when(schemeRepository.findById(scheme.getId())).thenReturn(Mono.just(scheme));

        StepVerifier.create(schemeService.findById(scheme.getId()))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(scheme.getId());
                assertThat(result.getName()).isEqualTo("Gold Plan");
            })
            .verifyComplete();

        verify(schemeRepository).findById(scheme.getId());
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(schemeRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.findById(id))
            .expectError(SchemeNotFoundException.class)
            .verify();

        verify(schemeRepository).findById(id);
    }

    @Test
    void create_validRequest_createsScheme() {
        var request = new CreateSchemeRequest("Gold Plan", "Premium plan", null, null, LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Gold Plan")).thenReturn(Mono.just(false));
        when(schemeRepository.save(any(Scheme.class))).thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getName()).isEqualTo("Gold Plan");
                assertThat(saved.getStatus()).isEqualTo("active");
                assertThat(saved.getSchemeType()).isEqualTo("medical_aid");
                // feedback_effective_date_snap: SchemeService snaps
                // effectiveDate to the 1st of the month.
                assertThat(saved.getEffectiveDate()).isEqualTo(request.effectiveDate().withDayOfMonth(1));
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getCreatedAt()).isNotNull();
                assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(schemeRepository).existsByName("Gold Plan");
        verify(schemeRepository).save(any(Scheme.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_duplicateName_throwsConflict() {
        var request = new CreateSchemeRequest("Gold Plan", "Premium plan", null, null, LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Gold Plan")).thenReturn(Mono.just(true));

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectError(DuplicateSchemeException.class)
            .verify();

        verify(schemeRepository).existsByName("Gold Plan");
        verify(schemeRepository, never()).save(any());
    }

    @Test
    void deactivate_existingScheme_setsStatusInactive() {
        var scheme = createTestScheme();

        when(schemeRepository.findById(scheme.getId())).thenReturn(Mono.just(scheme));
        when(schemeRepository.save(any(Scheme.class))).thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.deactivate(scheme.getId(), actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(deactivated -> {
                assertThat(deactivated.getStatus()).isEqualTo("inactive");
                assertThat(deactivated.getUpdatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(schemeRepository).findById(scheme.getId());
        verify(schemeRepository).save(any(Scheme.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void createBenefit_validRequest_createsBenefit() {
        var schemeId = UUID.randomUUID();
        var request = new CreateSchemeBenefitRequest(
            schemeId, "Outpatient", "outpatient",
            new BigDecimal("5000.00"), new BigDecimal("500.00"), new BigDecimal("1000.00"),
            "USD", 30, "Outpatient benefit", null, null, null, null
        );

        var parentScheme = new Scheme();
        parentScheme.setId(schemeId);
        parentScheme.setName("Gold");
        parentScheme.setCurrencyCode("USD");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parentScheme));
        when(schemeBenefitRepository.save(any(SchemeBenefit.class)))
            .thenAnswer(inv -> Mono.just(assignBenefitIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.createBenefit(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getName()).isEqualTo("Outpatient");
                assertThat(saved.getBenefitType()).isEqualTo("outpatient");
                assertThat(saved.getSchemeId()).isEqualTo(schemeId);
                assertThat(saved.getAnnualLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
                assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                assertThat(saved.getId()).isNotNull();
            })
            .verifyComplete();

        verify(schemeBenefitRepository).save(any(SchemeBenefit.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void createAgeGroup_validRequest_createsAgeGroup() {
        var schemeId = UUID.randomUUID();
        var request = new CreateAgeGroupRequest(
            schemeId, "Adult", 18, 65,
            new BigDecimal("200.00"), "USD"
        );

        var parentScheme = new Scheme();
        parentScheme.setId(schemeId);
        parentScheme.setName("Gold");
        parentScheme.setCurrencyCode("USD");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parentScheme));
        when(ageGroupRepository.save(any(AgeGroup.class)))
            .thenAnswer(inv -> Mono.just(assignAgeGroupIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.createAgeGroup(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getName()).isEqualTo("Adult");
                assertThat(saved.getMinAge()).isEqualTo(18);
                assertThat(saved.getMaxAge()).isEqualTo(65);
                assertThat(saved.getContributionAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
                assertThat(saved.getSchemeId()).isEqualTo(schemeId);
                assertThat(saved.getId()).isNotNull();
            })
            .verifyComplete();

        verify(ageGroupRepository).save(any(AgeGroup.class));
        verify(auditPublisher).publish(any());
    }

    // ---- Regression: null @Id on create (bugfix) ----

    @Test
    void create_leavesIdNullSoSaveTakesInsertPath() {
        var request = new CreateSchemeRequest("Bronze Plan", null, null, null, LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Bronze Plan")).thenReturn(Mono.just(false));
        // Snapshot the @Id at the moment save() is invoked. We can't use
        // ArgumentCaptor here because assignIdIfMissing mutates the same
        // entity reference in-place to simulate a DB-assigned UUID, so by
        // the time the captor is read the id is no longer null.
        var idAtSave = new java.util.concurrent.atomic.AtomicReference<UUID>();
        when(schemeRepository.save(any(Scheme.class)))
            .thenAnswer(inv -> {
                Scheme s = inv.getArgument(0);
                idAtSave.set(s.getId());
                return Mono.just(assignIdIfMissing(s));
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        // Spring Data R2DBC routes save(entity) to INSERT only when @Id is null.
        // Pre-setting the ID makes save() take the UPDATE path and fail with
        // "Row does not exist." The Scheme passed to save() must arrive with
        // id == null — this regression test locks the bugfix in place.
        assertThat(idAtSave.get()).isNull();
    }

    // ---- Regression: currency normalization (USD default, upper-casing) ----

    @Test
    void create_blankCurrency_defaultsToUSD() {
        var request = new CreateSchemeRequest("Plan A", null, null, null, LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Plan A")).thenReturn(Mono.just(false));
        var captor = ArgumentCaptor.forClass(Scheme.class);
        when(schemeRepository.save(captor.capture()))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(captor.getValue().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void update_currencyCodeNormalisedToUppercase() {
        var existing = createTestScheme();
        existing.setCurrencyCode("USD");

        when(schemeRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        var captor = ArgumentCaptor.forClass(Scheme.class);
        when(schemeRepository.save(captor.capture()))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var request = new UpdateSchemeRequest(null, null, null, null, null, "EUR", null, null, null);

        StepVerifier.create(schemeService.update(existing.getId(), request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(captor.getValue().getCurrencyCode()).isEqualTo("EUR");
    }

    // ---- Regression: single-currency-per-scheme enforcement ----

    @Test
    void createBenefit_currencyMismatch_throwsIllegalArgument() {
        var schemeId = UUID.randomUUID();
        var parent = new Scheme();
        parent.setId(schemeId);
        parent.setName("Gold");
        parent.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parent));

        var request = new CreateSchemeBenefitRequest(
            schemeId, "Outpatient", "outpatient",
            new BigDecimal("5000.00"), null, null,
            "EUR", 30, null, null, null, null, null
        );

        StepVerifier.create(schemeService.createBenefit(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(IllegalArgumentException.class);
                assertThat(err.getMessage()).contains("EUR").contains("USD")
                    .contains("single-currency");
            })
            .verify();

        verify(schemeBenefitRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void createBenefit_blankCurrency_inheritsFromScheme() {
        var schemeId = UUID.randomUUID();
        var parent = new Scheme();
        parent.setId(schemeId);
        parent.setName("Gold");
        parent.setCurrencyCode("EUR");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parent));

        var captor = ArgumentCaptor.forClass(SchemeBenefit.class);
        when(schemeBenefitRepository.save(captor.capture()))
            .thenAnswer(inv -> Mono.just(assignBenefitIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var request = new CreateSchemeBenefitRequest(
            schemeId, "Outpatient", "outpatient",
            new BigDecimal("5000.00"), null, null,
            null, 30, null, null, null, null, null
        );

        StepVerifier.create(schemeService.createBenefit(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(captor.getValue().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    void createAgeGroup_currencyMismatch_throwsIllegalArgument() {
        var schemeId = UUID.randomUUID();
        var parent = new Scheme();
        parent.setId(schemeId);
        parent.setCurrencyCode("USD");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parent));

        var request = new CreateAgeGroupRequest(
            schemeId, "Adult", 18, 65,
            new BigDecimal("200.00"), "ZWL"
        );

        StepVerifier.create(schemeService.createAgeGroup(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectError(IllegalArgumentException.class)
            .verify();

        verify(ageGroupRepository, never()).save(any());
    }

    // ---- Regression: audit envelope emitted on every mutation ----

    @Test
    void create_publishesAuditEventWithCorrectShape() {
        var request = new CreateSchemeRequest("Audit Plan", "desc", "hmo", null, LocalDate.now(), null, "usd", null, null, null);

        when(schemeRepository.existsByName("Audit Plan")).thenReturn(Mono.just(false));
        when(schemeRepository.save(any(Scheme.class)))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        var auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        when(auditPublisher.publish(auditCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-7")))
            .expectNextCount(1)
            .verifyComplete();

        var evt = auditCaptor.getValue();
        assertThat(evt.entityType()).isEqualTo("Scheme");
        assertThat(evt.action()).isEqualTo("CREATE");
        assertThat(evt.actorId()).isEqualTo(actorId);
        assertThat(evt.tenantId()).isEqualTo("tenant-7");
        assertThat(evt.oldValue()).isNull();
        assertThat(evt.newValue())
            .containsEntry("name", "Audit Plan")
            .containsEntry("status", "active")
            .containsEntry("schemeType", "hmo");
        assertThat(evt.entityId()).isNotBlank();
        assertThat(evt.correlationId()).isNotBlank();
    }

    @Test
    void deactivate_publishesAuditEventWithStatusTransition() {
        var scheme = createTestScheme();
        scheme.setStatus("active");

        when(schemeRepository.findById(scheme.getId())).thenReturn(Mono.just(scheme));
        when(schemeRepository.save(any(Scheme.class)))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        var auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        when(auditPublisher.publish(auditCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.deactivate(scheme.getId(), actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-7")))
            .expectNextCount(1)
            .verifyComplete();

        var evt = auditCaptor.getValue();
        assertThat(evt.action()).isEqualTo("UPDATE");
        assertThat(evt.oldValue()).containsEntry("status", "active");
        assertThat(evt.newValue()).containsEntry("status", "inactive");
    }

    @Test
    void publishAudit_usesUnknownTenant_whenContextMissing() {
        // If a downstream caller forgets to set TENANT_ID on the context, the
        // audit envelope still emits with a sentinel tenant so the audit
        // trail is never silently dropped.
        var request = new CreateSchemeRequest("Headless", null, null, null, LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Headless")).thenReturn(Mono.just(false));
        when(schemeRepository.save(any(Scheme.class)))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        var auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        when(auditPublisher.publish(auditCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(auditCaptor.getValue().tenantId()).isEqualTo("unknown");
    }

    // ---- Insurance-line gating ----

    @Test
    void create_persistsInsuranceLine_whenProvided() {
        var request = new CreateSchemeRequest("Life Plan", null, "term_life", "LIFE",
                LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Life Plan")).thenReturn(Mono.just(false));
        var captor = ArgumentCaptor.forClass(Scheme.class);
        when(schemeRepository.save(captor.capture()))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(captor.getValue().getInsuranceLine()).isEqualTo("LIFE");
    }

    @Test
    void create_defaultsToHEALTH_whenLineOmitted() {
        var request = new CreateSchemeRequest("Default Plan", null, null, null,
                LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Default Plan")).thenReturn(Mono.just(false));
        var captor = ArgumentCaptor.forClass(Scheme.class);
        when(schemeRepository.save(captor.capture()))
            .thenAnswer(inv -> Mono.just(assignIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        assertThat(captor.getValue().getInsuranceLine()).isEqualTo("HEALTH");
    }

    @Test
    void create_unknownInsuranceLine_returns422() {
        var request = new CreateSchemeRequest("Bad Plan", null, null, "BANANA",
                LocalDate.now(), null, null, null, null, null);

        when(schemeRepository.existsByName("Bad Plan")).thenReturn(Mono.just(false));

        StepVerifier.create(schemeService.create(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("BANANA");
            })
            .verify();

        verify(schemeRepository, never()).save(any());
    }

    @Test
    void createBenefit_assetCentricScheme_returns422() {
        var schemeId = UUID.randomUUID();
        var vehicleScheme = new Scheme();
        vehicleScheme.setId(schemeId);
        vehicleScheme.setName("Fleet Comp");
        vehicleScheme.setCurrencyCode("USD");
        vehicleScheme.setInsuranceLine("VEHICLE");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(vehicleScheme));

        var request = new CreateSchemeBenefitRequest(
            schemeId, "Anything", "x",
            new BigDecimal("1000.00"), null, null,
            "USD", 0, null, null, null, null, null
        );

        StepVerifier.create(schemeService.createBenefit(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("VEHICLE");
            })
            .verify();

        verify(schemeBenefitRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void createAgeGroup_assetCentricScheme_returns422() {
        var schemeId = UUID.randomUUID();
        var propertyScheme = new Scheme();
        propertyScheme.setId(schemeId);
        propertyScheme.setName("Home Buildings");
        propertyScheme.setCurrencyCode("USD");
        propertyScheme.setInsuranceLine("PROPERTY");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(propertyScheme));

        var request = new CreateAgeGroupRequest(
            schemeId, "Adult", 18, 65,
            new BigDecimal("200.00"), "USD"
        );

        StepVerifier.create(schemeService.createAgeGroup(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(422);
                assertThat(err.getMessage()).contains("PROPERTY");
            })
            .verify();

        verify(ageGroupRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void createBenefit_personCentricLifeScheme_succeeds() {
        var schemeId = UUID.randomUUID();
        var lifeScheme = new Scheme();
        lifeScheme.setId(schemeId);
        lifeScheme.setName("Term Life");
        lifeScheme.setCurrencyCode("USD");
        lifeScheme.setInsuranceLine("LIFE");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(lifeScheme));
        when(schemeBenefitRepository.save(any(SchemeBenefit.class)))
            .thenAnswer(inv -> Mono.just(assignBenefitIdIfMissing(inv.getArgument(0))));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var request = new CreateSchemeBenefitRequest(
            schemeId, "Sum Assured", "lump_sum",
            new BigDecimal("100000.00"), null, null,
            "USD", 90, null, null, null, null, null
        );

        StepVerifier.create(schemeService.createBenefit(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNextCount(1)
            .verifyComplete();

        verify(schemeBenefitRepository).save(any(SchemeBenefit.class));
    }

    // ---- Helpers ----

    private static Scheme assignIdIfMissing(Scheme s) {
        if (s.getId() == null) s.setId(UUID.randomUUID());
        return s;
    }

    private static SchemeBenefit assignBenefitIdIfMissing(SchemeBenefit b) {
        if (b.getId() == null) b.setId(UUID.randomUUID());
        return b;
    }

    private static AgeGroup assignAgeGroupIdIfMissing(AgeGroup g) {
        if (g.getId() == null) g.setId(UUID.randomUUID());
        return g;
    }

    private Scheme createTestScheme() {
        var scheme = new Scheme();
        scheme.setId(UUID.randomUUID());
        scheme.setName("Gold Plan");
        scheme.setSchemeType("medical_aid");
        scheme.setStatus("active");
        scheme.setEffectiveDate(LocalDate.now());
        scheme.setCreatedAt(Instant.now());
        scheme.setUpdatedAt(Instant.now());
        scheme.setCreatedBy(UUID.randomUUID());
        scheme.setUpdatedBy(UUID.randomUUID());
        return scheme;
    }
}
