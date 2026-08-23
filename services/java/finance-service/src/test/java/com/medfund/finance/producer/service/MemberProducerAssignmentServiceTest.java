package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.entity.MemberProducerAssignment;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.MemberProducerAssignmentRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberProducerAssignmentServiceTest {

    @Mock MemberProducerAssignmentRepository assignmentRepository;
    @Mock ProducerRepository producerRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks MemberProducerAssignmentService service;

    // ── assign — happy path ────────────────────────────────────────────────

    @Test
    void assign_noPrior_insertsOpenRow_effectiveFromSnappedToFirstOfMonth() {
        UUID memberId = UUID.randomUUID();
        UUID producerId = UUID.randomUUID();
        var req = new AssignMemberRequest(producerId, LocalDate.of(2026, 3, 17), "New assignment");
        Producer p = activeProducer(producerId);
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(p));
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.empty());
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            MemberProducerAssignment m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return Mono.just(m);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.assign(memberId, req, "sys", "admin@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(resp -> {
                    assertThat(resp.effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
                    assertThat(resp.producerId()).isEqualTo(producerId);
                    assertThat(resp.effectiveTo()).isNull();
                })
                .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void assign_closesPriorOpenRow_thenInsertsNewOne() {
        UUID memberId = UUID.randomUUID();
        UUID oldProducerId = UUID.randomUUID();
        UUID newProducerId = UUID.randomUUID();
        MemberProducerAssignment prior = new MemberProducerAssignment();
        prior.setId(UUID.randomUUID());
        prior.setMemberId(memberId);
        prior.setProducerId(oldProducerId);
        prior.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        prior.setCreatedAt(OffsetDateTime.now());

        var req = new AssignMemberRequest(newProducerId, LocalDate.of(2026, 4, 15), "Reassign");
        when(producerRepository.findById(newProducerId)).thenReturn(Mono.just(activeProducer(newProducerId)));
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.just(prior));
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            MemberProducerAssignment m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return Mono.just(m);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.assign(memberId, req, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(resp -> assertThat(resp.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1)))
                .verifyComplete();

        // Prior closed at 2026-04-01 - 1 day = 2026-03-31
        assertThat(prior.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 3, 31));

        // Two audit events: CLOSE + CREATE
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(cap.capture());
        List<String> actions = cap.getAllValues().stream().map(AuditEvent::action).toList();
        assertThat(actions).containsExactly("CLOSE", "CREATE");
    }

    @Test
    void assign_backdatedBeforePriorStart_rejects() {
        UUID memberId = UUID.randomUUID();
        UUID producerId = UUID.randomUUID();
        MemberProducerAssignment prior = new MemberProducerAssignment();
        prior.setId(UUID.randomUUID());
        prior.setMemberId(memberId);
        prior.setProducerId(UUID.randomUUID());
        prior.setEffectiveFrom(LocalDate.of(2026, 6, 1));

        var req = new AssignMemberRequest(producerId, LocalDate.of(2026, 3, 15), "Backdate");
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(activeProducer(producerId)));
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.just(prior));

        StepVerifier.create(service.assign(memberId, req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void assign_missingProducer_errors() {
        UUID memberId = UUID.randomUUID();
        UUID producerId = UUID.randomUUID();
        var req = new AssignMemberRequest(producerId, LocalDate.of(2026, 3, 1), null);
        when(producerRepository.findById(producerId)).thenReturn(Mono.empty());

        StepVerifier.create(service.assign(memberId, req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void assign_inactiveProducer_errors() {
        UUID memberId = UUID.randomUUID();
        UUID producerId = UUID.randomUUID();
        Producer p = activeProducer(producerId);
        p.setActive(false);
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(p));

        var req = new AssignMemberRequest(producerId, LocalDate.of(2026, 3, 1), null);
        StepVerifier.create(service.assign(memberId, req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class).verify();
    }

    // ── close current ──────────────────────────────────────────────────────

    @Test
    void closeCurrent_snapsEffectiveToToLastDayOfMonth() {
        UUID memberId = UUID.randomUUID();
        MemberProducerAssignment open = new MemberProducerAssignment();
        open.setId(UUID.randomUUID());
        open.setMemberId(memberId);
        open.setProducerId(UUID.randomUUID());
        open.setEffectiveFrom(LocalDate.now().withDayOfMonth(1).minusMonths(2));
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.just(open));
        when(assignmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.closeCurrent(memberId, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .verifyComplete();

        assertThat(open.getEffectiveTo())
                .isEqualTo(LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()));
    }

    @Test
    void closeCurrent_noOpenAssignment_errors() {
        UUID memberId = UUID.randomUUID();
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.empty());

        StepVerifier.create(service.closeCurrent(memberId, "sys", "a@b"))
                .expectError(IllegalArgumentException.class).verify();
    }

    // ── queries ────────────────────────────────────────────────────────────

    @Test
    void currentFor_delegates() {
        UUID memberId = UUID.randomUUID();
        MemberProducerAssignment m = new MemberProducerAssignment();
        m.setId(UUID.randomUUID());
        m.setMemberId(memberId);
        m.setProducerId(UUID.randomUUID());
        m.setEffectiveFrom(LocalDate.now());
        when(assignmentRepository.findOpenByMember(memberId)).thenReturn(Mono.just(m));

        StepVerifier.create(service.currentFor(memberId)).expectNextCount(1).verifyComplete();
    }

    @Test
    void countOpenForProducer_delegates() {
        UUID producerId = UUID.randomUUID();
        when(assignmentRepository.countOpenByProducer(producerId)).thenReturn(Mono.just(47L));
        StepVerifier.create(service.countOpenForProducer(producerId))
                .expectNext(47L).verifyComplete();
    }

    private Producer activeProducer(UUID id) {
        Producer p = new Producer();
        p.setId(id);
        p.setProducerCode("BRK-X");
        p.setName("X");
        p.setHomeCurrency("USD");
        p.setActive(true);
        return p;
    }
}
