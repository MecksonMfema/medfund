package com.medfund.finance.producer.service;

import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import com.medfund.finance.producer.repository.ProducerBackfillCandidateRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProducerBackfillReviewServiceTest {

    @Mock ProducerBackfillCandidateRepository candidateRepository;
    @Mock TreatyRepository treatyRepository;
    @Mock ProducerRepository producerRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks ProducerBackfillReviewService service;

    @Test
    void accept_pendingCandidate_setsTreatyProducerIdAndRejectsSiblings() {
        UUID treatyId = UUID.randomUUID();
        Producer alpha = producer("Alpha");
        Treaty treaty = treaty(treatyId, "TR-001", "Alpha ref");
        ProducerBackfillCandidate accepting = candidate(treatyId, alpha.getId(), "PENDING", "0.930");
        ProducerBackfillCandidate sibling  = candidate(treatyId, UUID.randomUUID(), "PENDING", "0.700");

        when(candidateRepository.findById(accepting.getId())).thenReturn(Mono.just(accepting));
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(treatyRepository.save(any(Treaty.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(candidateRepository.findSiblingsPendingFor(treatyId, accepting.getId()))
                .thenReturn(Flux.just(sibling));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.accept(accepting.getId(), UUID.randomUUID().toString(), "ops@medfund.io"))
                .verifyComplete();

        ArgumentCaptor<Treaty> treatyCap = ArgumentCaptor.forClass(Treaty.class);
        verify(treatyRepository).save(treatyCap.capture());
        assertThat(treatyCap.getValue().getProducerId()).isEqualTo(alpha.getId());

        ArgumentCaptor<ProducerBackfillCandidate> candCap = ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository, times(2)).save(candCap.capture());
        List<ProducerBackfillCandidate> saved = candCap.getAllValues();
        assertThat(saved.get(0).getStatus()).isEqualTo("REJECTED"); // sibling first (siblings rejected before accepted candidate is finalized)
        assertThat(saved.get(1).getStatus()).isEqualTo("ACCEPTED");

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(3)).publish(auditCap.capture());
        assertThat(auditCap.getAllValues()).extracting(AuditEvent::entityType)
                .contains("Treaty", "ProducerBackfillCandidate");
        assertThat(auditCap.getAllValues()).extracting(AuditEvent::entityName)
                .contains("TR-001");
    }

    @Test
    void accept_alreadyResolved_errorsIllegalState() {
        ProducerBackfillCandidate resolved = candidate(UUID.randomUUID(), UUID.randomUUID(),
                "ACCEPTED", "0.950");
        when(candidateRepository.findById(resolved.getId())).thenReturn(Mono.just(resolved));

        StepVerifier.create(service.accept(resolved.getId(), null, null))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void accept_missingCandidate_errorsIllegalArgument() {
        UUID id = UUID.randomUUID();
        when(candidateRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.accept(id, null, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void reject_pendingCandidate_marksRejectedAndLeavesTreatyUntouched() {
        UUID treatyId = UUID.randomUUID();
        ProducerBackfillCandidate rejecting = candidate(treatyId, UUID.randomUUID(), "PENDING", "0.610");

        when(candidateRepository.findById(rejecting.getId())).thenReturn(Mono.just(rejecting));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.reject(rejecting.getId(), null, "ops@medfund.io"))
                .verifyComplete();

        verify(treatyRepository, never()).save(any(Treaty.class));
        ArgumentCaptor<ProducerBackfillCandidate> cap = ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void reject_alreadyResolved_errorsIllegalState() {
        ProducerBackfillCandidate resolved = candidate(UUID.randomUUID(), UUID.randomUUID(),
                "REJECTED", "0.550");
        when(candidateRepository.findById(resolved.getId())).thenReturn(Mono.just(resolved));

        StepVerifier.create(service.reject(resolved.getId(), null, null))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void listPending_enrichesWithTreatyAndProducerContext() {
        UUID treatyId = UUID.randomUUID();
        Producer alpha = producer("Alpha");
        Treaty treaty = treaty(treatyId, "TR-042", "Alpha ref");
        ProducerBackfillCandidate c = candidate(treatyId, alpha.getId(), "PENDING", "0.720");

        when(candidateRepository.findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 50))
                .thenReturn(Flux.just(c));
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.just(treaty));
        when(producerRepository.findById(alpha.getId())).thenReturn(Mono.just(alpha));

        StepVerifier.create(service.listPending(0, 50))
                .assertNext(row -> {
                    assertThat(row.treatyRef()).isEqualTo("TR-042");
                    assertThat(row.candidateProducerName()).isEqualTo("Alpha");
                    assertThat(row.confidenceScore()).isEqualByComparingTo(new BigDecimal("0.720"));
                })
                .verifyComplete();
    }

    @Test
    void listPending_deletedTreatyOrProducer_survivesWithPlaceholders() {
        UUID treatyId = UUID.randomUUID();
        UUID producerId = UUID.randomUUID();
        ProducerBackfillCandidate c = candidate(treatyId, producerId, "PENDING", "0.680");

        when(candidateRepository.findByStatusOrderByConfidenceScoreDesc("PENDING", 0, 50))
                .thenReturn(Flux.just(c));
        when(treatyRepository.findById(treatyId)).thenReturn(Mono.empty());
        when(producerRepository.findById(producerId)).thenReturn(Mono.empty());

        StepVerifier.create(service.listPending(0, 50))
                .assertNext(row -> {
                    assertThat(row.treatyRef()).isEqualTo("(deleted treaty)");
                    assertThat(row.candidateProducerName()).isEqualTo("(deleted producer)");
                })
                .verifyComplete();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Producer producer(String name) {
        Producer p = new Producer();
        p.setId(UUID.randomUUID());
        p.setProducerCode("BRK-" + name.toUpperCase());
        p.setName(name);
        p.setHomeCurrency("USD");
        p.setActive(true);
        return p;
    }

    private static Treaty treaty(UUID id, String ref, String producerRef) {
        Treaty t = new Treaty();
        t.setId(id);
        t.setTreatyRef(ref);
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(java.time.LocalDate.of(2026, 1, 1));
        t.setExpiryDate(java.time.LocalDate.of(2027, 1, 1));
        t.setStatus("ACTIVE");
        t.setProducerRef(producerRef);
        return t;
    }

    private static ProducerBackfillCandidate candidate(UUID treatyId, UUID candidateProducerId,
                                                        String status, String score) {
        ProducerBackfillCandidate c = new ProducerBackfillCandidate();
        c.setId(UUID.randomUUID());
        c.setTreatyId(treatyId);
        c.setTreatyProducerRef("legacy ref");
        c.setCandidateProducerId(candidateProducerId);
        c.setConfidenceScore(new BigDecimal(score));
        c.setMatchStrategy("LEVENSHTEIN");
        c.setStatus(status);
        c.setCreatedAt(OffsetDateTime.now());
        return c;
    }
}
