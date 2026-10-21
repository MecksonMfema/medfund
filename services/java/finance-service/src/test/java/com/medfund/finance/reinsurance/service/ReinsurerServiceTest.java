package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.UpdateReinsurerRequest;
import com.medfund.finance.reinsurance.entity.Reinsurer;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReinsurerServiceTest {

    @Mock ReinsurerRepository repository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks ReinsurerService service;

    @Test
    void list_paged_returnsEnvelope() {
        Reinsurer r1 = seed("Munich Re", true);
        Reinsurer r2 = seed("Swiss Re", true);
        when(repository.findPage(0, 50)).thenReturn(Flux.just(r1, r2));
        when(repository.countAll()).thenReturn(Mono.just(2L));

        StepVerifier.create(service.list(0, 50, null))
                .assertNext(page -> {
                    assertThat(page.total()).isEqualTo(2L);
                    assertThat(page.content()).hasSize(2);
                    assertThat(page.content().get(0).name()).isEqualTo("Munich Re");
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

    @Test
    void create_publishesCreateAudit_withReinsurerNameAsEntityName() {
        var req = new CreateReinsurerRequest("Munich Re", "ops@munichre.com", "Munich",
                "DE", "EUR", "AA+");
        when(repository.save(any())).thenAnswer(inv -> {
            Reinsurer r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(req, UUID.randomUUID().toString(), "admin@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a"))
        )
                .assertNext(resp -> {
                    assertThat(resp.name()).isEqualTo("Munich Re");
                    assertThat(resp.active()).isTrue();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("CREATE");
        assertThat(cap.getValue().entityType()).isEqualTo("Reinsurer");
        assertThat(cap.getValue().entityName()).isEqualTo("Munich Re");
        assertThat(cap.getValue().actorEmail()).isEqualTo("admin@test.example");
    }

    @Test
    void update_missing_errors() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(id, sampleUpdate(true), "sys", "a@b"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void update_flippingActive_emitsUpdateAudit_withChangedFields() {
        Reinsurer existing = seed("Munich Re", true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.update(existing.getId(), sampleUpdate(false), "sys", "admin@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a"))
        )
                .assertNext(resp -> assertThat(resp.active()).isFalse())
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditPublisher).publish(cap.capture());
        AuditEvent ev = cap.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(List.of(ev.changedFields())).contains("active");
    }

    private Reinsurer seed(String name, boolean active) {
        Reinsurer r = new Reinsurer();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setActive(active);
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        return r;
    }

    private UpdateReinsurerRequest sampleUpdate(boolean active) {
        return new UpdateReinsurerRequest("Munich Re", "new@munichre.com", "Munich",
                "DE", "EUR", "AA", active);
    }
}
