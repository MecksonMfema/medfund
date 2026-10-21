package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.BordereauPeriodExport;
import com.medfund.finance.reinsurance.repository.BordereauPeriodExportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BordereauPeriodExportService}. Covers the upsert
 * semantics (insert vs increment) and the treaty-nullable branching.
 */
@ExtendWith(MockitoExtension.class)
class BordereauPeriodExportServiceTest {

    @Mock BordereauPeriodExportRepository repository;
    @InjectMocks BordereauPeriodExportService service;

    private static final String REPORT_KEY = "REINSURANCE_CESSION_BORDEREAU";

    @Test
    void markExported_firstTime_insertsRowWithExportCountOne() {
        UUID reinsurerId = UUID.randomUUID();
        UUID treatyId = UUID.randomUUID();
        when(repository.findByCompositeKey(reinsurerId, treatyId, REPORT_KEY, 2026, 3))
                .thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            BordereauPeriodExport row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return Mono.just(row);
        });

        StepVerifier.create(service.markExported(reinsurerId, treatyId, REPORT_KEY, 2026, 3,
                        UUID.randomUUID().toString(), "admin@medfund"))
                .assertNext(row -> {
                    assertThat(row.getExportCount()).isEqualTo(1);
                    assertThat(row.getFirstExportedAt()).isNotNull();
                    assertThat(row.getActorEmail()).isEqualTo("admin@medfund");
                })
                .verifyComplete();

        ArgumentCaptor<BordereauPeriodExport> cap = ArgumentCaptor.forClass(BordereauPeriodExport.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getReportKey()).isEqualTo(REPORT_KEY);
        assertThat(cap.getValue().getYear()).isEqualTo(2026);
        assertThat(cap.getValue().getQuarter()).isEqualTo(3);
    }

    @Test
    void markExported_secondTime_incrementsExportCount() {
        UUID reinsurerId = UUID.randomUUID();
        UUID treatyId = UUID.randomUUID();
        OffsetDateTime firstAt = OffsetDateTime.now().minusDays(2);
        BordereauPeriodExport existing = new BordereauPeriodExport();
        existing.setId(UUID.randomUUID());
        existing.setReinsurerId(reinsurerId);
        existing.setTreatyId(treatyId);
        existing.setReportKey(REPORT_KEY);
        existing.setYear(2026);
        existing.setQuarter(3);
        existing.setFirstExportedAt(firstAt);
        existing.setExportCount(1);

        when(repository.findByCompositeKey(reinsurerId, treatyId, REPORT_KEY, 2026, 3))
                .thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.markExported(reinsurerId, treatyId, REPORT_KEY, 2026, 3,
                        "sys", "sys@medfund"))
                .assertNext(row -> {
                    assertThat(row.getExportCount()).isEqualTo(2);
                    assertThat(row.getFirstExportedAt()).isEqualTo(firstAt);
                })
                .verifyComplete();
    }

    @Test
    void markExported_nullTreaty_usesNoTreatyLookup() {
        UUID reinsurerId = UUID.randomUUID();
        when(repository.findByCompositeKeyNoTreaty(reinsurerId, REPORT_KEY, 2026, 3))
                .thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            BordereauPeriodExport row = inv.getArgument(0);
            row.setId(UUID.randomUUID());
            return Mono.just(row);
        });

        StepVerifier.create(service.markExported(reinsurerId, null, REPORT_KEY, 2026, 3,
                        "sys", "sys@medfund"))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).findByCompositeKeyNoTreaty(reinsurerId, REPORT_KEY, 2026, 3);
    }

    @Test
    void firstExportedAt_present_returnsTimestamp() {
        UUID reinsurerId = UUID.randomUUID();
        UUID treatyId = UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.now();
        BordereauPeriodExport row = new BordereauPeriodExport();
        row.setFirstExportedAt(when);
        when(repository.findByCompositeKey(reinsurerId, treatyId, REPORT_KEY, 2026, 1))
                .thenReturn(Mono.just(row));

        StepVerifier.create(service.firstExportedAt(reinsurerId, treatyId, REPORT_KEY, 2026, 1))
                .expectNext(when)
                .verifyComplete();
    }

    @Test
    void firstExportedAt_missing_returnsEmpty() {
        UUID reinsurerId = UUID.randomUUID();
        when(repository.findByCompositeKeyNoTreaty(eq(reinsurerId), any(), eq(2026), eq(1)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.firstExportedAt(reinsurerId, null, REPORT_KEY, 2026, 1))
                .verifyComplete();
    }
}
