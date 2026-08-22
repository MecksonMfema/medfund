package com.medfund.finance.reinsurance.job;

import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.scheduler.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReinsuranceTreatyPremiumExecutor}. Verifies the
 * shape of the write (PREMIUM cession, source_event_type=TREATY_INCEPTION,
 * source_event_id=treaty.id), idempotency via the pre-write existence
 * check + the UNIQUE-violation swallow, and the JobType binding.
 */
@ExtendWith(MockitoExtension.class)
class ReinsuranceTreatyPremiumExecutorTest {

    @Mock TreatyRepository treatyRepository;
    @Mock CessionRepository cessionRepository;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks ReinsuranceTreatyPremiumExecutor executor;

    @Test
    void getJobType_isReinsuranceTreatyPremium() {
        assertThat(executor.getJobType()).isEqualTo(JobType.REINSURANCE_TREATY_PREMIUM);
    }

    @Test
    void execute_activeXolWithPremium_writesInceptionCession() {
        UUID treatyId = UUID.randomUUID();
        Treaty xol = treaty(treatyId, "EXCESS_OF_LOSS", new BigDecimal("50000.00"));

        when(treatyRepository.findActiveNonProportionalWithExpectedPremium())
                .thenReturn(Flux.just(xol));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(treatyId), eq("PREMIUM")))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(executor.execute("tenant-1", "{}"))
                .verifyComplete();

        ArgumentCaptor<Cession> cap = ArgumentCaptor.forClass(Cession.class);
        verify(cessionRepository, times(1)).save(cap.capture());
        Cession saved = cap.getValue();
        assertThat(saved.getCessionType()).isEqualTo("PREMIUM");
        assertThat(saved.getSource()).isEqualTo("AUTOMATIC");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSourceEventId()).isEqualTo(treatyId);
        assertThat(saved.getSourceEventType()).isEqualTo("TREATY_INCEPTION");
        assertThat(saved.getBasisAmount()).isEqualByComparingTo("50000.00");
        assertThat(saved.getCededAmount()).isEqualByComparingTo("50000.00");
        assertThat(saved.getCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getTreatyLayerId()).isNull();

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(auditCap.capture());
        assertThat(auditCap.getValue().entityName())
                .startsWith("Inception premium cession on treaty");
    }

    @Test
    void execute_alreadyCeded_isIdempotentNoOp() {
        UUID treatyId = UUID.randomUUID();
        Treaty xol = treaty(treatyId, "EXCESS_OF_LOSS", new BigDecimal("50000.00"));
        Cession existing = new Cession();
        existing.setId(UUID.randomUUID());

        when(treatyRepository.findActiveNonProportionalWithExpectedPremium())
                .thenReturn(Flux.just(xol));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(treatyId), eq("PREMIUM")))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(executor.execute("tenant-1", "{}"))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void execute_uniqueViolationOnRace_swallowsSilently() {
        UUID treatyId = UUID.randomUUID();
        Treaty xol = treaty(treatyId, "STOP_LOSS", new BigDecimal("30000.00"));

        when(treatyRepository.findActiveNonProportionalWithExpectedPremium())
                .thenReturn(Flux.just(xol));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_cession_source_event")));

        StepVerifier.create(executor.execute("tenant-1", "{}"))
                .verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void execute_multipleTreaties_writesOnePerTreaty() {
        Treaty a = treaty(UUID.randomUUID(), "EXCESS_OF_LOSS", new BigDecimal("50000.00"));
        Treaty b = treaty(UUID.randomUUID(), "STOP_LOSS", new BigDecimal("30000.00"));

        when(treatyRepository.findActiveNonProportionalWithExpectedPremium())
                .thenReturn(Flux.just(a, b));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(executor.execute("tenant-1", "{}"))
                .verifyComplete();

        verify(cessionRepository, times(2)).save(any());
        verify(auditPublisher, times(2)).publish(any());
    }

    @Test
    void execute_noActiveNonProportional_writesNothing() {
        when(treatyRepository.findActiveNonProportionalWithExpectedPremium())
                .thenReturn(Flux.empty());

        StepVerifier.create(executor.execute("tenant-1", "{}"))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
    }

    // ---- helpers ----

    private static Treaty treaty(UUID id, String type, BigDecimal annualPremium) {
        Treaty t = new Treaty();
        t.setId(id);
        t.setTreatyRef("T-" + id.toString().substring(0, 8));
        t.setTreatyType(type);
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(LocalDate.now().minusDays(30));
        t.setExpiryDate(LocalDate.now().plusDays(300));
        t.setStatus("ACTIVE");
        t.setExpectedAnnualPremium(annualPremium);
        t.setActivatedAt(OffsetDateTime.now().minusDays(30));
        return t;
    }
}
