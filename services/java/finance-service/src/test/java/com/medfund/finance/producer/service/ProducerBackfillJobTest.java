package com.medfund.finance.producer.service;

import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.entity.ProducerBackfillCandidate;
import com.medfund.finance.producer.repository.ProducerBackfillCandidateRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProducerBackfillJobTest {

    @Mock TreatyRepository treatyRepository;
    @Mock ProducerRepository producerRepository;
    @Mock ProducerBackfillCandidateRepository candidateRepository;
    @InjectMocks ProducerBackfillJob job;

    private final ProducerBackfillProgressService progressService = new ProducerBackfillProgressService();

    @BeforeEach
    void wireProgressService() {
        job = new ProducerBackfillJob(treatyRepository, producerRepository,
                candidateRepository, progressService);
    }

    // ── similarity ────────────────────────────────────────────────────────

    @Test
    void similarity_identicalStrings_scoresOne() {
        assertThat(ProducerBackfillJob.similarity("Acme Brokers", "Acme Brokers"))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void similarity_caseInsensitive_andWhitespaceTolerant() {
        assertThat(ProducerBackfillJob.similarity(" acme brokers ", "ACME BROKERS"))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void similarity_bothEmpty_scoresOne() {
        assertThat(ProducerBackfillJob.similarity("", "")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(ProducerBackfillJob.similarity(null, null)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void similarity_completelyDifferent_scoresLow() {
        BigDecimal score = ProducerBackfillJob.similarity("abcd", "wxyz");
        assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void similarity_nearMatch_scoresBetweenThresholds() {
        BigDecimal score = ProducerBackfillJob.similarity("Acme Brokers Ltd", "Acme Broker Ltd");
        assertThat(score).isGreaterThan(new BigDecimal("0.900"));
        assertThat(score).isLessThan(BigDecimal.ONE);
    }

    // ── job pipeline ──────────────────────────────────────────────────────

    @Test
    void runBackfill_exactMatch_autoAcceptsAndSetsTreatyProducerId() {
        Producer alpha = producer("Alpha Brokers");
        Treaty treaty  = treaty("TR-001", "Alpha Brokers");

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.just(alpha));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(treaty));
        when(treatyRepository.save(any(Treaty.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        ArgumentCaptor<Treaty> treatyCap = ArgumentCaptor.forClass(Treaty.class);
        verify(treatyRepository).save(treatyCap.capture());
        assertThat(treatyCap.getValue().getProducerId()).isEqualTo(alpha.getId());

        ArgumentCaptor<ProducerBackfillCandidate> candCap =
                ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository).save(candCap.capture());
        assertThat(candCap.getValue().getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void runBackfill_nearMatchAboveAutoAcceptThreshold_autoAccepts() {
        Producer alpha = producer("Acme Brokers Ltd");
        Treaty treaty  = treaty("TR-002", "Acme Broker Ltd");

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.just(alpha));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(treaty));
        when(treatyRepository.save(any(Treaty.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        verify(treatyRepository).save(any(Treaty.class));
        ArgumentCaptor<ProducerBackfillCandidate> cap = ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void runBackfill_mediumMatch_queuesPendingNoTreatyUpdate() {
        Producer alpha = producer("Alpha Brokers Ltd");
        Treaty treaty  = treaty("TR-003", "Alfa Brokrs");

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.just(alpha));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(treaty));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        verify(treatyRepository, never()).save(any(Treaty.class));
        ArgumentCaptor<ProducerBackfillCandidate> cap = ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void runBackfill_belowMinScore_skips() {
        Producer alpha = producer("Alpha Brokers");
        Treaty treaty  = treaty("TR-004", "zzzzzzzz");

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.just(alpha));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(treaty));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        verify(treatyRepository, never()).save(any(Treaty.class));
        verify(candidateRepository, never()).save(any(ProducerBackfillCandidate.class));
        var progress = progressService.get(new UUID(0L, 0L)).orElseThrow();
        assertThat(progress.getSkipped()).isEqualTo(1);
    }

    @Test
    void runBackfill_multipleProducers_topThreeOnlyKept() {
        List<Producer> producers = List.of(
                producer("Alpha Brokers"),   // exact match → auto-accept
                producer("Alpha Broker"),    // near
                producer("Alpha"),           // near
                producer("Alp"),             // borderline
                producer("Beta Brokers"));   // low
        Treaty treaty = treaty("TR-005", "Alpha Brokers");

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.fromIterable(producers));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(treaty));
        when(treatyRepository.save(any(Treaty.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        // 1 auto-accept + up to 2 pending siblings (rest filtered by score OR top-3 cap)
        ArgumentCaptor<ProducerBackfillCandidate> cap = ArgumentCaptor.forClass(ProducerBackfillCandidate.class);
        verify(candidateRepository, times(3)).save(cap.capture());
        long accepted = cap.getAllValues().stream().filter(c -> "ACCEPTED".equals(c.getStatus())).count();
        long pending  = cap.getAllValues().stream().filter(c -> "PENDING".equals(c.getStatus())).count();
        assertThat(accepted).isEqualTo(1);
        assertThat(pending).isEqualTo(2);
    }

    @Test
    void runBackfill_noProducers_skipsEveryTreaty() {
        Treaty t1 = treaty("TR-006", "Alpha");
        Treaty t2 = treaty("TR-007", "Beta");
        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.empty());
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.just(t1, t2));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        verify(candidateRepository, never()).save(any(ProducerBackfillCandidate.class));
        var progress = progressService.get(new UUID(0L, 0L)).orElseThrow();
        assertThat(progress.getSkipped()).isEqualTo(2);
    }

    @Test
    void runBackfill_noTreatiesToBackfill_completesWithZeroCounts() {
        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.empty());
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull()).thenReturn(Flux.empty());

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        var progress = progressService.get(new UUID(0L, 0L)).orElseThrow();
        assertThat(progress.getProcessed()).isZero();
        assertThat(progress.getAutoAccepted()).isZero();
        assertThat(progress.isRunning()).isFalse();
    }

    @Test
    void runBackfill_batchesUpTo200Treaties_progressesAll() {
        Producer alpha = producer("Alpha Brokers");
        List<Treaty> many = IntStream.range(0, 250)
                .mapToObj(i -> treaty("TR-B-" + i, "Alpha Brokers"))
                .toList();

        when(producerRepository.findActiveOrderByName()).thenReturn(Flux.just(alpha));
        when(treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull())
                .thenReturn(Flux.fromIterable(many));
        when(treatyRepository.save(any(Treaty.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(candidateRepository.save(any(ProducerBackfillCandidate.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(job.runBackfill(null, null)).verifyComplete();

        var progress = progressService.get(new UUID(0L, 0L)).orElseThrow();
        assertThat(progress.getProcessed()).isEqualTo(250);
        assertThat(progress.getAutoAccepted()).isEqualTo(250);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Producer producer(String name) {
        Producer p = new Producer();
        p.setId(UUID.randomUUID());
        p.setProducerCode("BRK-" + name.replaceAll("\\W+", "").toUpperCase());
        p.setName(name);
        p.setHomeCurrency("USD");
        p.setActive(true);
        return p;
    }

    private static Treaty treaty(String ref, String producerRef) {
        Treaty t = new Treaty();
        t.setId(UUID.randomUUID());
        t.setTreatyRef(ref);
        t.setTreatyType("QUOTA_SHARE");
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(java.time.LocalDate.of(2026, 1, 1));
        t.setExpiryDate(java.time.LocalDate.of(2027, 1, 1));
        t.setStatus("ACTIVE");
        t.setProducerRef(producerRef);
        return t;
    }
}
