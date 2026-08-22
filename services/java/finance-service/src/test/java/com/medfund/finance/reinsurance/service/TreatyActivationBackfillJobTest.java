package com.medfund.finance.reinsurance.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.entity.TreatyApplicableLine;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreatyActivationBackfillJobTest {

    @Mock TreatyApplicableLineRepository applicableLineRepository;
    @Mock ClaimsClient claimsClient;
    @Mock CessionService cessionService;
    @Mock BackfillProgressService progressService;

    @InjectMocks TreatyActivationBackfillJob job;

    @Test
    void backfill_iteratesEveryApplicableLineAndDispatchesEach() {
        Treaty treaty = treaty(LocalDate.of(2026, 1, 1));
        TreatyApplicableLine health = line(treaty.getId(), "HEALTH");
        TreatyApplicableLine life = line(treaty.getId(), "LIFE");
        when(applicableLineRepository.findByTreatyId(treaty.getId()))
                .thenReturn(Flux.just(health, life));

        UUID claim1 = UUID.randomUUID();
        UUID claim2 = UUID.randomUUID();
        UUID claim3 = UUID.randomUUID();
        when(claimsClient.streamAdjudicatedClaimsForBackfill(eq("HEALTH"),
                        eq(treaty.getInceptionDate()), eq(100)))
                .thenReturn(Flux.just(claimRow(claim1), claimRow(claim2)));
        when(claimsClient.streamAdjudicatedClaimsForBackfill(eq("LIFE"),
                        eq(treaty.getInceptionDate()), eq(100)))
                .thenReturn(Flux.just(claimRow(claim3)));

        when(cessionService.processAdjudicatedClaim(any(), anyString(), anyString()))
                .thenReturn(Flux.just(new Cession()));

        StepVerifier.create(
                job.backfill(treaty, "sys", "sys@test")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a"))
        )
                .verifyComplete();

        ArgumentCaptor<ClaimAdjudicatedEvent> cap =
                ArgumentCaptor.forClass(ClaimAdjudicatedEvent.class);
        verify(cessionService, times(3))
                .processAdjudicatedClaim(cap.capture(), anyString(), anyString());
        List<ClaimAdjudicatedEvent> dispatched = cap.getAllValues();
        assertThat(dispatched).extracting(ClaimAdjudicatedEvent::claimId)
                .containsExactlyInAnyOrder(claim1, claim2, claim3);
        assertThat(dispatched).allSatisfy(e -> {
            assertThat(e.decision()).isEqualTo("APPROVED");
            assertThat(e.tenantId()).isEqualTo("tenant-a");
        });
        verify(progressService, times(3)).incrementProcessed(treaty.getId());
    }

    @Test
    void backfill_rowFailure_incrementsFailedAndContinues() {
        Treaty treaty = treaty(LocalDate.of(2026, 1, 1));
        when(applicableLineRepository.findByTreatyId(treaty.getId()))
                .thenReturn(Flux.just(line(treaty.getId(), "HEALTH")));

        UUID ok  = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        when(claimsClient.streamAdjudicatedClaimsForBackfill(eq("HEALTH"),
                        eq(treaty.getInceptionDate()), eq(100)))
                .thenReturn(Flux.just(claimRow(bad), claimRow(ok)));
        when(cessionService.processAdjudicatedClaim(any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    ClaimAdjudicatedEvent e = inv.getArgument(0);
                    if (e.claimId().equals(bad)) {
                        return Flux.error(new RuntimeException("boom"));
                    }
                    return Flux.just(new Cession());
                });

        StepVerifier.create(
                job.backfill(treaty, "sys", "sys@test")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a"))
        )
                .verifyComplete();

        verify(progressService).incrementFailed(treaty.getId());
        verify(progressService).incrementProcessed(treaty.getId());
    }

    @Test
    void backfill_noApplicableLines_writesNothing() {
        Treaty treaty = treaty(LocalDate.of(2026, 1, 1));
        when(applicableLineRepository.findByTreatyId(treaty.getId())).thenReturn(Flux.empty());

        StepVerifier.create(job.backfill(treaty, "sys", "sys@test")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .verifyComplete();

        verify(cessionService, times(0))
                .processAdjudicatedClaim(any(), anyString(), anyString());
    }

    private static Treaty treaty(LocalDate inception) {
        Treaty t = new Treaty();
        t.setId(UUID.randomUUID());
        t.setTreatyRef("HEALTH-QS-2026");
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(inception);
        t.setExpiryDate(inception.plusYears(1));
        t.setStatus("ACTIVE");
        t.setActivatedAt(OffsetDateTime.now());
        return t;
    }

    private static TreatyApplicableLine line(UUID treatyId, String line) {
        TreatyApplicableLine l = new TreatyApplicableLine();
        l.setTreatyId(treatyId);
        l.setInsuranceLine(line);
        return l;
    }

    private static ClaimsClient.AdjudicatedClaimRow claimRow(UUID claimId) {
        return new ClaimsClient.AdjudicatedClaimRow(
                claimId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "HEALTH",
                new BigDecimal("1000.00"),
                "USD",
                OffsetDateTime.parse("2026-02-15T10:00:00Z"));
    }
}
