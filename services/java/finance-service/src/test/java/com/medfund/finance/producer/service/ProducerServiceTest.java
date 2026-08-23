package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.UpdateProducerRequest;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
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
class ProducerServiceTest {

    @Mock ProducerRepository repository;
    @Mock MemberProducerAssignmentRepository assignmentRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks ProducerService service;

    // ── list / get ─────────────────────────────────────────────────────────

    @Test
    void list_paged_returnsEnvelope() {
        Producer a = seed("BRK-001", "Alpha Brokers");
        Producer b = seed("BRK-002", "Beta Brokers");
        when(repository.findPage(0, 50)).thenReturn(Flux.just(a, b));
        when(repository.countAll()).thenReturn(Mono.just(2L));

        StepVerifier.create(service.list(0, 50, null))
                .assertNext(page -> {
                    assertThat(page.total()).isEqualTo(2L);
                    assertThat(page.content()).hasSize(2);
                    assertThat(page.content().get(0).producerCode()).isEqualTo("BRK-001");
                })
                .verifyComplete();
    }

    @Test
    void list_activeFilter_usesActiveQueries() {
        when(repository.findPageByActive(true, 0, 50)).thenReturn(Flux.empty());
        when(repository.countByActive(true)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.list(0, 50, Boolean.TRUE))
                .assertNext(page -> assertThat(page.content()).isEmpty())
                .verifyComplete();
    }

    @Test
    void get_missing_errors400() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(id))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    void create_happyPath_publishesCreateAudit_withProducerCodeAsEntityName() {
        var req = new CreateProducerRequest("BRK-100", "New Broker Ltd",
                "ops@broker.example", "+263711000000", "ZW",
                "USD", null, null, null);
        when(repository.findByProducerCode("BRK-100")).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            Producer p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return Mono.just(p);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(req, UUID.randomUUID().toString(), "admin@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .assertNext(resp -> {
                    assertThat(resp.producerCode()).isEqualTo("BRK-100");
                    assertThat(resp.homeCurrency()).isEqualTo("USD");
                    assertThat(resp.active()).isTrue();
                    assertThat(resp.activatedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        AuditEvent ev = cap.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityType()).isEqualTo("Producer");
        assertThat(ev.entityName()).isEqualTo("BRK-100");
        assertThat(ev.actorEmail()).isEqualTo("admin@test.example");
    }

    @Test
    void create_duplicateCode_errors() {
        var req = new CreateProducerRequest("BRK-DUP", "Dup", null, null, null,
                "USD", null, null, null);
        when(repository.findByProducerCode("BRK-DUP")).thenReturn(Mono.just(seed("BRK-DUP", "Existing")));

        StepVerifier.create(
                service.create(req, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void create_withParent_walksAncestryForCycleCheck_noCycle_passes() {
        UUID parentId = UUID.randomUUID();
        var req = new CreateProducerRequest("BRK-CHILD", "Child", null, null, null,
                "USD", parentId, null, null);
        Producer parent = seed("BRK-PARENT", "Parent");
        parent.setId(parentId);
        when(repository.findByProducerCode("BRK-CHILD")).thenReturn(Mono.empty());
        // Ancestry of the parent = just the parent itself (no cycle possible on create — no selfId).
        when(repository.findAncestryOf(parentId)).thenReturn(Flux.just(parent));
        when(repository.save(any())).thenAnswer(inv -> {
            Producer p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return Mono.just(p);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(req, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .assertNext(resp -> assertThat(resp.parentProducerId()).isEqualTo(parentId))
                .verifyComplete();
    }

    // ── update ─────────────────────────────────────────────────────────────

    @Test
    void update_missing_errors() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(id, sampleUpdate("BRK-1", true), "sys", "a@b"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void update_flippingActive_emitsUpdateAudit_withChangedFields() {
        Producer existing = seed("BRK-EDIT", "Editable");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.update(existing.getId(), sampleUpdate("Editable Renamed", false),
                                "sys", "admin@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .assertNext(resp -> {
                    assertThat(resp.active()).isFalse();
                    assertThat(resp.name()).isEqualTo("Editable Renamed");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        AuditEvent ev = cap.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(List.of(ev.changedFields())).contains("active", "name");
        assertThat(ev.entityName()).isEqualTo("BRK-EDIT");
    }

    @Test
    void update_selfParent_rejects() {
        Producer existing = seed("BRK-SELF", "Self");
        UUID id = existing.getId();
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        UpdateProducerRequest req = new UpdateProducerRequest(
                "Self", null, null, null, "USD", id, null, null, true);

        StepVerifier.create(service.update(id, req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void update_reparentingWouldCreateCycle_rejects() {
        // existing is A; we try to set A.parent = B where B's ancestry already includes A.
        Producer existing = seed("BRK-A", "A");
        UUID childId = UUID.randomUUID();
        Producer child = seed("BRK-B", "B");
        child.setId(childId);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.findAncestryOf(childId))
                .thenReturn(Flux.just(child, existing));   // B -> A (existing)

        UpdateProducerRequest req = new UpdateProducerRequest(
                "A", null, null, null, "USD", childId, null, null, true);

        StepVerifier.create(service.update(existing.getId(), req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── ancestry / children / search ───────────────────────────────────────

    @Test
    void ancestry_returnsChain() {
        UUID id = UUID.randomUUID();
        when(repository.findAncestryOf(id)).thenReturn(Flux.just(seed("BRK-A", "A"), seed("BRK-P", "Parent")));

        StepVerifier.create(service.ancestry(id))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void search_blank_returnsEmpty() {
        StepVerifier.create(service.search("  ", 20)).verifyComplete();
    }

    @Test
    void search_delegatesToRepository() {
        when(repository.search("alp", 20)).thenReturn(Flux.just(seed("BRK-A", "Alpha")));
        StepVerifier.create(service.search("alp", 20))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ── terminate ──────────────────────────────────────────────────────────

    @Test
    void terminate_happyPath_flipsActive_closesAssignments_publishesAudit() {
        Producer existing = seed("BRK-TERM", "To Terminate");
        UUID id = existing.getId();
        MemberProducerAssignment openA = openAssignment(id, LocalDate.of(2026, 1, 1));
        MemberProducerAssignment openB = openAssignment(id, LocalDate.of(2026, 2, 1));
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(assignmentRepository.findOpenByProducer(id)).thenReturn(Flux.just(openA, openB));
        when(assignmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.terminate(id, LocalDate.of(2026, 8, 12), "sys", "admin@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(resp -> {
                    assertThat(resp.active()).isFalse();
                    assertThat(resp.terminatedAt()).isNotNull();
                })
                .verifyComplete();

        // Assignments closed with effective_to = last-day-of-month of 2026-08-12
        LocalDate expectedClose = LocalDate.of(2026, 8, 31);
        assertThat(openA.getEffectiveTo()).isEqualTo(expectedClose);
        assertThat(openB.getEffectiveTo()).isEqualTo(expectedClose);

        // Three audit events: two CLOSE (one per assignment) + one TERMINATE
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(3)).publish(cap.capture());
        List<String> actions = cap.getAllValues().stream().map(AuditEvent::action).toList();
        assertThat(actions).containsExactly("CLOSE", "CLOSE", "TERMINATE");

        AuditEvent producerEvent = cap.getAllValues().get(2);
        assertThat(producerEvent.entityType()).isEqualTo("Producer");
        assertThat(producerEvent.entityName()).isEqualTo("BRK-TERM");
        assertThat(producerEvent.actorEmail()).isEqualTo("admin@t");
    }

    @Test
    void terminate_alreadyTerminated_errors409() {
        Producer existing = seed("BRK-DONE", "Already Terminated");
        existing.setActive(false);
        UUID id = existing.getId();
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.terminate(id, LocalDate.now(), "sys", "a@b"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void terminate_effectiveDate_snapsToLastDayOfMonth() {
        Producer existing = seed("BRK-SNAP", "Snap Test");
        UUID id = existing.getId();
        MemberProducerAssignment open = openAssignment(id, LocalDate.of(2026, 1, 1));
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(assignmentRepository.findOpenByProducer(id)).thenReturn(Flux.just(open));
        when(assignmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // Mid-month date should snap to last day
        StepVerifier.create(
                service.terminate(id, LocalDate.of(2026, 5, 14), "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(open.getEffectiveTo())
                .isEqualTo(LocalDate.of(2026, 5, 14).with(TemporalAdjusters.lastDayOfMonth()));
    }

    @Test
    void terminate_noOpenAssignments_stillSucceeds_publishesTerminateAudit() {
        Producer existing = seed("BRK-EMPTY", "No Assignments");
        UUID id = existing.getId();
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(assignmentRepository.findOpenByProducer(id)).thenReturn(Flux.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.terminate(id, LocalDate.now(), "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(resp -> assertThat(resp.active()).isFalse())
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("TERMINATE");
    }

    @Test
    void terminate_snappedBeforeAssignmentStart_pinsCloseToAssignmentStart() {
        Producer existing = seed("BRK-FUTURE", "Future");
        UUID id = existing.getId();
        // Assignment starts 2026-12-01; terminate effective 2026-05-14
        // (last-day-snap = 2026-05-31, which is before the assignment start).
        // The close date should pin to the assignment start rather than orphan
        // the row with effective_to < effective_from (V093 CHECK constraint).
        MemberProducerAssignment open = openAssignment(id, LocalDate.of(2026, 12, 1));
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(assignmentRepository.findOpenByProducer(id)).thenReturn(Flux.just(open));
        when(assignmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.terminate(id, LocalDate.of(2026, 5, 14), "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(open.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MemberProducerAssignment openAssignment(UUID producerId, LocalDate from) {
        MemberProducerAssignment m = new MemberProducerAssignment();
        m.setId(UUID.randomUUID());
        m.setMemberId(UUID.randomUUID());
        m.setProducerId(producerId);
        m.setEffectiveFrom(from);
        m.setCreatedAt(OffsetDateTime.now());
        return m;
    }

    private Producer seed(String code, String name) {
        Producer p = new Producer();
        p.setId(UUID.randomUUID());
        p.setProducerCode(code);
        p.setName(name);
        p.setHomeCurrency("USD");
        p.setActive(true);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }

    private UpdateProducerRequest sampleUpdate(String name, boolean active) {
        return new UpdateProducerRequest(name, null, null, null, "USD",
                null, BigDecimal.valueOf(5), null, active);
    }
}
