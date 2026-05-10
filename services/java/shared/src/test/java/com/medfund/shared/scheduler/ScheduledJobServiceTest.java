package com.medfund.shared.scheduler;

import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledJobServiceTest {

    @Mock ScheduledJobRepository jobRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks ScheduledJobService scheduledJobService;

    @Test
    void seedDefaults_creates6Jobs() {
        when(jobRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        UUID tenantId = UUID.randomUUID();

        StepVerifier.create(scheduledJobService.seedDefaults(tenantId, "system"))
            .verifyComplete();

        verify(jobRepository, times(6)).save(any(ScheduledJobConfig.class));
    }

    @Test
    void seedDefaults_stampsTenantIdOnEveryConfig() {
        UUID tenantId = UUID.randomUUID();
        when(jobRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(scheduledJobService.seedDefaults(tenantId, "system"))
            .verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(ScheduledJobConfig.class);
        verify(jobRepository, times(6)).save(captor.capture());
        captor.getAllValues().forEach(c ->
            assertThat(c.getTenantId()).isEqualTo(tenantId));
    }

    @Test
    void findByJobType_delegates() {
        var config = new ScheduledJobConfig();
        config.setJobType("BILLING_CYCLE");
        when(jobRepository.findByJobType("BILLING_CYCLE")).thenReturn(Mono.just(config));

        StepVerifier.create(scheduledJobService.findByJobType("BILLING_CYCLE"))
            .assertNext(c -> assertThat(c.getJobType()).isEqualTo("BILLING_CYCLE"))
            .verifyComplete();
    }
}
