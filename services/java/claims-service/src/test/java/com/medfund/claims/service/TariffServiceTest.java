package com.medfund.claims.service;

import com.medfund.claims.dto.CreateTariffCodeRequest;
import com.medfund.claims.dto.CreateTariffScheduleRequest;
import com.medfund.claims.dto.TariffCodeFilterParams;
import com.medfund.claims.dto.TariffCodeRow;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.entity.TariffModifier;
import com.medfund.claims.entity.TariffSchedule;
import com.medfund.claims.repository.TariffCodeQueryRepository;
import com.medfund.claims.repository.TariffCodeRepository;
import com.medfund.claims.repository.TariffModifierQueryRepository;
import com.medfund.claims.repository.TariffModifierRepository;
import com.medfund.claims.repository.TariffScheduleRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TariffServiceTest {

    @Mock
    private TariffScheduleRepository tariffScheduleRepository;

    @Mock
    private TariffCodeRepository tariffCodeRepository;

    @Mock
    private TariffCodeQueryRepository tariffCodeQueryRepository;

    @Mock
    private TariffModifierRepository tariffModifierRepository;

    @Mock
    private TariffModifierQueryRepository tariffModifierQueryRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private TariffService tariffService;

    private String actorId;
    private static final String ACTOR_EMAIL = "actor@test.example";

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID().toString();
    }

    @Test
    void findAllSchedules_returnsSchedules() {
        TariffSchedule schedule1 = createTestSchedule("Schedule A");
        TariffSchedule schedule2 = createTestSchedule("Schedule B");

        when(tariffScheduleRepository.findAllOrderByEffectiveDateDesc())
                .thenReturn(Flux.just(schedule1, schedule2));

        StepVerifier.create(tariffService.findAllSchedules())
                .expectNext(schedule1)
                .expectNext(schedule2)
                .verifyComplete();

        verify(tariffScheduleRepository).findAllOrderByEffectiveDateDesc();
    }

    @Test
    void createSchedule_validRequest_createsSchedule() {
        var request = new CreateTariffScheduleRequest(
                "Test Schedule", LocalDate.now(), LocalDate.now().plusYears(1), "INTERNAL"
        );

        when(tariffScheduleRepository.save(any())).thenAnswer(inv -> {
            var saved = (TariffSchedule) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                tariffService.createSchedule(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(schedule -> {
                    assertThat(schedule.getName()).isEqualTo("Test Schedule");
                    assertThat(schedule.getStatus()).isEqualTo("ACTIVE");
                    // TariffService snaps effectiveDate to the 1st of the
                    // month (see memory: feedback_effective_date_snap).
                    assertThat(schedule.getEffectiveDate()).isEqualTo(request.effectiveDate().withDayOfMonth(1));
                    assertThat(schedule.getSource()).isEqualTo("INTERNAL");
                    assertThat(schedule.getId()).isNotNull();
                    assertThat(schedule.getCreatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(tariffScheduleRepository).save(any(TariffSchedule.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void findCodeByCode_existing_returnsCode() {
        TariffCode tariffCode = createTestTariffCode("TC001", "Consultation");

        when(tariffCodeRepository.findByCode("TC001")).thenReturn(Mono.just(tariffCode));

        StepVerifier.create(tariffService.findCodeByCode("TC001"))
                .assertNext(code -> {
                    assertThat(code.getCode()).isEqualTo("TC001");
                    assertThat(code.getDescription()).isEqualTo("Consultation");
                })
                .verifyComplete();

        verify(tariffCodeRepository).findByCode("TC001");
    }

    @Test
    void createCode_validRequest_createsCode() {
        UUID scheduleId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        var request = new CreateTariffCodeRequest(
                scheduleId, "TC100", "X-Ray", categoryId,
                new BigDecimal("250.00"), "USD", false
        );

        when(tariffCodeRepository.save(any())).thenAnswer(inv -> {
            var saved = (com.medfund.claims.entity.TariffCode) inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                tariffService.createCode(request, actorId, ACTOR_EMAIL)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(code -> {
                    assertThat(code.getCode()).isEqualTo("TC100");
                    assertThat(code.getDescription()).isEqualTo("X-Ray");
                    // V063 — resolver reads categoryId, not the legacy free-text.
                    assertThat(code.getCategoryId()).isEqualTo(categoryId);
                    assertThat(code.getUnitPrice()).isEqualByComparingTo(new BigDecimal("250.00"));
                    assertThat(code.getScheduleId()).isEqualTo(scheduleId);
                    assertThat(code.getRequiresPreAuth()).isFalse();
                    assertThat(code.getId()).isNotNull();
                })
                .verifyComplete();

        verify(tariffCodeRepository).save(any(TariffCode.class));
        verify(auditPublisher).publish(any());
    }

    @Test
    void applyModifiers_percentageModifier_appliesCorrectly() {
        TariffModifier modifier = new TariffModifier();
        modifier.setId(UUID.randomUUID());
        modifier.setCode("MOD-PCT");
        modifier.setName("50% Increase");
        modifier.setAdjustmentType("PERCENTAGE");
        modifier.setAdjustmentValue(new BigDecimal("1.50"));
        modifier.setIsActive(true);

        when(tariffModifierRepository.findByCode("MOD-PCT")).thenReturn(Mono.just(modifier));

        StepVerifier.create(tariffService.applyModifiers(new BigDecimal("100.00"), List.of("MOD-PCT")))
                .assertNext(result ->
                        assertThat(result).isEqualByComparingTo(new BigDecimal("150.00"))
                )
                .verifyComplete();
    }

    @Test
    void applyModifiers_fixedModifier_appliesCorrectly() {
        TariffModifier modifier = new TariffModifier();
        modifier.setId(UUID.randomUUID());
        modifier.setCode("MOD-FIX");
        modifier.setName("Fixed Surcharge");
        modifier.setAdjustmentType("FIXED");
        modifier.setAdjustmentValue(new BigDecimal("50.00"));
        modifier.setIsActive(true);

        when(tariffModifierRepository.findByCode("MOD-FIX")).thenReturn(Mono.just(modifier));

        StepVerifier.create(tariffService.applyModifiers(new BigDecimal("100.00"), List.of("MOD-FIX")))
                .assertNext(result ->
                        assertThat(result).isEqualByComparingTo(new BigDecimal("150.00"))
                )
                .verifyComplete();
    }

    // ---- Helpers ----

    private TariffSchedule createTestSchedule(String name) {
        var schedule = new TariffSchedule();
        schedule.setId(UUID.randomUUID());
        schedule.setName(name);
        schedule.setEffectiveDate(LocalDate.now());
        schedule.setEndDate(LocalDate.now().plusYears(1));
        schedule.setSource("INTERNAL");
        schedule.setStatus("ACTIVE");
        schedule.setCreatedAt(Instant.now());
        return schedule;
    }

    @Test
    void searchCodesPaged_wrapsQueryRepoRowsInPageResponse() {
        UUID scheduleId = UUID.randomUUID();
        var row = new TariffCodeRow(
                UUID.randomUUID(), scheduleId, "TC001", "Consultation",
                UUID.randomUUID(), "General practice",
                new BigDecimal("50.00"), "USD", false);

        var params = new TariffCodeFilterParams(
                scheduleId, "TC0", null, null, "code", "asc", 0, 50);

        when(tariffCodeQueryRepository.search(any(), eq(50), eq(0)))
                .thenReturn(Flux.just(row));
        when(tariffCodeQueryRepository.count(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(tariffService.searchCodesPaged(params))
                .assertNext(pageResp -> {
                    assertThat(pageResp.content()).containsExactly(row);
                    assertThat(pageResp.total()).isEqualTo(1L);
                    assertThat(pageResp.page()).isZero();
                    assertThat(pageResp.size()).isEqualTo(50);
                    assertThat(pageResp.totalPages()).isEqualTo(1);
                })
                .verifyComplete();

        verify(tariffCodeQueryRepository).search(any(), eq(50), eq(0));
        verify(tariffCodeQueryRepository).count(any());
    }

    @Test
    void searchCodesPaged_clampsSizeAndPage() {
        // Guards the "one page won't blow up the client" contract even when
        // a caller asks for size=99999 or a negative page.
        var params = new TariffCodeFilterParams(
                null, null, null, null, "code", "asc", -3, 99999);

        when(tariffCodeQueryRepository.search(any(), eq(200), eq(0)))
                .thenReturn(Flux.empty());
        when(tariffCodeQueryRepository.count(any())).thenReturn(Mono.just(0L));

        StepVerifier.create(tariffService.searchCodesPaged(params))
                .assertNext(pageResp -> {
                    assertThat(pageResp.page()).isZero();
                    assertThat(pageResp.size()).isEqualTo(200);
                })
                .verifyComplete();
    }

    private TariffCode createTestTariffCode(String code, String description) {
        var tariffCode = new TariffCode();
        tariffCode.setId(UUID.randomUUID());
        tariffCode.setScheduleId(UUID.randomUUID());
        tariffCode.setCode(code);
        tariffCode.setDescription(description);
        tariffCode.setCategory("GENERAL");
        tariffCode.setUnitPrice(new BigDecimal("500.00"));
        tariffCode.setCurrencyCode("USD");
        tariffCode.setRequiresPreAuth(false);
        tariffCode.setCreatedAt(Instant.now());
        return tariffCode;
    }
}
