package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreateAgeGroupRequest;
import com.medfund.contributions.dto.CreateSchemeBenefitRequest;
import com.medfund.contributions.dto.CreateSchemeRequest;
import com.medfund.contributions.entity.AgeGroup;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.exception.DuplicateSchemeException;
import com.medfund.contributions.exception.SchemeNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private SchemeService schemeService;

    private final String actorId = UUID.randomUUID().toString();

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
        var request = new CreateSchemeRequest("Gold Plan", "Premium plan", null, LocalDate.now(), null, null);

        when(schemeRepository.existsByName("Gold Plan")).thenReturn(Mono.just(false));
        when(schemeRepository.save(any(Scheme.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.create(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getName()).isEqualTo("Gold Plan");
                assertThat(saved.getStatus()).isEqualTo("active");
                assertThat(saved.getSchemeType()).isEqualTo("medical_aid");
                assertThat(saved.getEffectiveDate()).isEqualTo(request.effectiveDate());
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
        var request = new CreateSchemeRequest("Gold Plan", "Premium plan", null, LocalDate.now(), null, null);

        when(schemeRepository.existsByName("Gold Plan")).thenReturn(Mono.just(true));

        StepVerifier.create(schemeService.create(request, actorId)
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
        when(schemeRepository.save(any(Scheme.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.deactivate(scheme.getId(), actorId)
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
            "USD", 30, "Outpatient benefit"
        );

        var parentScheme = new Scheme();
        parentScheme.setId(schemeId);
        parentScheme.setName("Gold");
        parentScheme.setCurrencyCode("USD");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parentScheme));
        when(schemeBenefitRepository.save(any(SchemeBenefit.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.createBenefit(request, actorId)
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
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(schemeService.createAgeGroup(request, actorId)
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

    // ---- Helpers ----

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
