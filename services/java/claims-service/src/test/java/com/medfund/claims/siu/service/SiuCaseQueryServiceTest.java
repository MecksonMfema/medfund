package com.medfund.claims.siu.service;

import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuCaseNote;
import com.medfund.claims.siu.entity.SiuEvidence;
import com.medfund.claims.siu.entity.SiuReferral;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import com.medfund.claims.siu.repository.SiuCaseNoteRepository;
import com.medfund.claims.siu.repository.SiuCaseRepository;
import com.medfund.claims.siu.repository.SiuEvidenceRepository;
import com.medfund.claims.siu.repository.SiuReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiuCaseQueryServiceTest {

    private SiuCaseRepository caseRepo;
    private SiuCaseNoteRepository noteRepo;
    private FraudFlagRepository flagRepo;
    private SiuEvidenceRepository evidenceRepo;
    private SiuReferralRepository referralRepo;
    private SiuCaseQueryService service;

    @BeforeEach
    void setUp() {
        caseRepo = mock(SiuCaseRepository.class);
        noteRepo = mock(SiuCaseNoteRepository.class);
        flagRepo = mock(FraudFlagRepository.class);
        evidenceRepo = mock(SiuEvidenceRepository.class);
        referralRepo = mock(SiuReferralRepository.class);
        service = new SiuCaseQueryService(caseRepo, noteRepo, flagRepo,
                evidenceRepo, referralRepo);
    }

    @Test
    void findAll_noFilter_returnsAllWithFlagCounts() {
        SiuCase k = openCase();
        when(caseRepo.findAll()).thenReturn(Flux.just(k));
        when(flagRepo.findAllBySiuCaseIdOrderByFlaggedAtDesc(k.getId()))
                .thenReturn(Flux.just(new FraudFlag(), new FraudFlag()));

        StepVerifier.create(service.findAll(null, null))
                .assertNext(row -> {
                    assertThat(row.id()).isEqualTo(k.getId());
                    assertThat(row.flagCount()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void findAll_statusFilter_usesStatusIndex() {
        SiuCase k = openCase();
        when(caseRepo.findAllByStatusOrderByOpenedAtDesc("OPEN"))
                .thenReturn(Flux.just(k));
        when(flagRepo.findAllBySiuCaseIdOrderByFlaggedAtDesc(k.getId()))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.findAll("OPEN", null))
                .assertNext(row -> assertThat(row.flagCount()).isZero())
                .verifyComplete();
        verify(caseRepo).findAllByStatusOrderByOpenedAtDesc("OPEN");
    }

    @Test
    void findAll_assignedToTakesPrecedenceOverStatus() {
        SiuCase k = openCase();
        UUID assignee = UUID.randomUUID();
        when(caseRepo.findAllByAssignedToOrderByOpenedAtDesc(assignee))
                .thenReturn(Flux.just(k));
        when(flagRepo.findAllBySiuCaseIdOrderByFlaggedAtDesc(k.getId()))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.findAll("OPEN", assignee))
                .expectNextCount(1)
                .verifyComplete();
        verify(caseRepo).findAllByAssignedToOrderByOpenedAtDesc(assignee);
    }

    @Test
    void findById_composesCaseWithFlagsNotesEvidenceAndReferrals() {
        SiuCase k = openCase();
        SiuCaseNote note = new SiuCaseNote();
        note.setId(UUID.randomUUID());
        note.setCaseId(k.getId());
        note.setNoteType("COMMENT");
        note.setBody("body");
        note.setAuthorId(UUID.randomUUID());
        note.setAuthorEmail("a@medfund.local");
        note.setCreatedAt(OffsetDateTime.now());

        FraudFlag flag = new FraudFlag();
        flag.setId(UUID.randomUUID());
        flag.setClaimId(UUID.randomUUID());

        SiuEvidence ev = new SiuEvidence();
        ev.setId(UUID.randomUUID());
        ev.setCaseId(k.getId());
        ev.setFileServiceRef("s3://bucket/1.pdf");
        ev.setDescription("Provider dump");
        ev.setEvidenceType("PROVIDER_RECORD");
        ev.setUploadedBy(UUID.randomUUID());
        ev.setUploadedByEmail("o@medfund.local");
        ev.setUploadedAt(OffsetDateTime.now());

        SiuReferral ref = new SiuReferral();
        ref.setId(UUID.randomUUID());
        ref.setCaseId(k.getId());
        ref.setReferralTo("LAW_ENFORCEMENT");
        ref.setReferralReference("ZRP-1");
        ref.setReferredBy(UUID.randomUUID());
        ref.setReferredByEmail("s@medfund.local");
        ref.setReferredAt(OffsetDateTime.now());

        when(caseRepo.findById(k.getId())).thenReturn(Mono.just(k));
        when(flagRepo.findAllBySiuCaseIdOrderByFlaggedAtDesc(k.getId()))
                .thenReturn(Flux.just(flag));
        when(noteRepo.findAllByCaseIdOrderByCreatedAtAsc(k.getId()))
                .thenReturn(Flux.just(note));
        when(evidenceRepo.findAllByCaseIdOrderByUploadedAtDesc(k.getId()))
                .thenReturn(Flux.just(ev));
        when(referralRepo.findAllByCaseIdOrderByReferredAtDesc(k.getId()))
                .thenReturn(Flux.just(ref));

        StepVerifier.create(service.findById(k.getId()))
                .assertNext(resp -> {
                    assertThat(resp.id()).isEqualTo(k.getId());
                    assertThat(resp.flags()).hasSize(1);
                    assertThat(resp.notes()).hasSize(1);
                    assertThat(resp.notes().get(0).body()).isEqualTo("body");
                    assertThat(resp.evidence()).hasSize(1);
                    assertThat(resp.evidence().get(0).evidenceType()).isEqualTo("PROVIDER_RECORD");
                    assertThat(resp.referrals()).hasSize(1);
                    assertThat(resp.referrals().get(0).referralTo()).isEqualTo("LAW_ENFORCEMENT");
                })
                .verifyComplete();
    }

    private SiuCase openCase() {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setId(UUID.randomUUID());
        k.setCaseNumber("SIU-2026-000001");
        k.setStatus("OPEN");
        k.setOpenedAt(now);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        return k;
    }
}
