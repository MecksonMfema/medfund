package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.repository.MemberProducerAssignmentRepository;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(MemberProducerAssignmentIT.SecurityStub.class)
class MemberProducerAssignmentIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000012";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProducerService producerService;
    @Autowired private MemberProducerAssignmentService assignmentService;
    @Autowired private MemberProducerAssignmentRepository assignmentRepository;
    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void assign_thenReassign_persistsHistory_andClosesPriorRow() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var brokerA = producerService.create(
                        new CreateProducerRequest("BRK-A-IT",
                                "Broker A IT", null, null, "ZW", "USD",
                                null, null, null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        var brokerB = producerService.create(
                        new CreateProducerRequest("BRK-B-IT",
                                "Broker B IT", null, null, "ZW", "USD",
                                null, null, null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        UUID memberId = UUID.randomUUID();

        // Initial assignment on 2026-03-15 (snaps to 2026-03-01)
        assignmentService.assign(memberId,
                        new AssignMemberRequest(brokerA.id(), LocalDate.of(2026, 3, 15), "initial"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Reassign to B on 2026-06-20 (snaps to 2026-06-01, closes prior at 2026-05-31)
        assignmentService.assign(memberId,
                        new AssignMemberRequest(brokerB.id(), LocalDate.of(2026, 6, 20), "reassign"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        var history = assignmentService.historyFor(memberId).collectList()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(history).hasSize(2);
        // Newest first
        assertThat(history.get(0).producerId()).isEqualTo(brokerB.id());
        assertThat(history.get(0).effectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(history.get(0).effectiveTo()).isNull();

        assertThat(history.get(1).producerId()).isEqualTo(brokerA.id());
        assertThat(history.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(history.get(1).effectiveTo()).isEqualTo(LocalDate.of(2026, 5, 31));

        Long openCount = assignmentService.countOpenForProducer(brokerB.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(openCount).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void partialUniqueIndex_rejectsSecondOpenRowForSameMember() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var broker = producerService.create(
                        new CreateProducerRequest("BRK-UNIQ-IT",
                                "Broker Unique IT", null, null, "ZW", "USD",
                                null, null, null),
                        "sys", "a@b")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        UUID memberId = UUID.randomUUID();

        assignmentService.assign(memberId,
                        new AssignMemberRequest(broker.id(), LocalDate.of(2026, 3, 1), "first"),
                        "sys", "a@b")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Bypass the app-layer guard by writing a second open row directly through
        // the repository — the DB partial UNIQUE index must reject it.
        var rogue = new com.medfund.finance.producer.entity.MemberProducerAssignment();
        rogue.setMemberId(memberId);
        rogue.setProducerId(broker.id());
        rogue.setEffectiveFrom(LocalDate.of(2026, 4, 1));

        assertThatThrownBy(() -> assignmentRepository.save(rogue)
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @WithTenant(TENANT_ID)
    void assign_backdatedBeforePriorOpenStart_rejectsWithoutCorrupting() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var broker = producerService.create(
                        new CreateProducerRequest("BRK-BACK-IT",
                                "Broker Backdate IT", null, null, "ZW", "USD",
                                null, null, null),
                        "sys", "a@b")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        UUID memberId = UUID.randomUUID();

        assignmentService.assign(memberId,
                        new AssignMemberRequest(broker.id(), LocalDate.of(2026, 6, 1), "initial"),
                        "sys", "a@b")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        assertThatThrownBy(() -> assignmentService.assign(memberId,
                        new AssignMemberRequest(broker.id(), LocalDate.of(2026, 3, 15), "backdate"),
                        "sys", "a@b")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);

        // History still has exactly one open row after the rejected attempt.
        var history = assignmentService.historyFor(memberId).collectList()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).effectiveTo()).isNull();
    }
}
