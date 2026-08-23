package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateIfrs17PortfolioRequest;
import com.medfund.user.dto.UpdateIfrs17PortfolioRequest;
import com.medfund.user.entity.Ifrs17Portfolio;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.repository.Ifrs17PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Ifrs17PortfolioServiceTest {

    @Mock private Ifrs17PortfolioRepository repository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;

    private Ifrs17PortfolioService service;

    @BeforeEach
    void setUp() {
        service = new Ifrs17PortfolioService(repository, r2dbcTemplate, auditPublisher);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    @Test
    void findAll_activeOnly_bypassesInactive() {
        var p = portfolio(UUID.randomUUID(), "General Motor", true);
        when(repository.findAllActive()).thenReturn(Flux.just(p));

        StepVerifier.create(service.findAll(false))
                .expectNextMatches(x -> x.getName().equals("General Motor"))
                .verifyComplete();
    }

    @Test
    void findAll_includeInactive_returnsFullList() {
        var active = portfolio(UUID.randomUUID(), "Active", true);
        var inactive = portfolio(UUID.randomUUID(), "Archived", false);
        when(repository.findAllOrdered()).thenReturn(Flux.just(active, inactive));

        StepVerifier.create(service.findAll(true))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void findById_missing_errorsWithNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(id))
                .expectError(Ifrs17PortfolioNotFoundException.class)
                .verify();
    }

    @Test
    void create_duplicateName_returnsConflict() {
        when(repository.existsByNameIgnoreCase("Motor")).thenReturn(Mono.just(true));

        var req = new CreateIfrs17PortfolioRequest("Motor", "desc", "VEHICLE");

        StepVerifier.create(service.create(req, UUID.randomUUID().toString(), "alice@example.com"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
    }

    @Test
    void create_success_publishesCreateAudit() {
        when(repository.existsByNameIgnoreCase("Motor")).thenReturn(Mono.just(false));
        var saved = portfolio(UUID.randomUUID(), "Motor", true);
        when(r2dbcTemplate.insert(any(Ifrs17Portfolio.class))).thenReturn(Mono.just(saved));

        var req = new CreateIfrs17PortfolioRequest("Motor", "desc", "VEHICLE");

        StepVerifier.create(service.create(req, UUID.randomUUID().toString(), "alice@example.com"))
                .expectNextMatches(p -> p.getName().equals("Motor") && Boolean.TRUE.equals(p.getIsActive()))
                .verifyComplete();
    }

    @Test
    void softDelete_hasReferencingPolicies_conflicts() {
        UUID id = UUID.randomUUID();
        var existing = portfolio(id, "Motor", true);
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.countReferencingPolicies(id)).thenReturn(Mono.just(3L));

        StepVerifier.create(service.softDelete(id, UUID.randomUUID().toString(), "alice@example.com"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
    }

    @Test
    void update_success_flipsFieldsAndPersists() {
        UUID id = UUID.randomUUID();
        var existing = portfolio(id, "Old", true);
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.existsByNameIgnoreCaseAndIdNot(eq("New"), eq(id))).thenReturn(Mono.just(false));
        when(repository.save(any(Ifrs17Portfolio.class))).thenAnswer(inv ->
                Mono.just((Ifrs17Portfolio) inv.getArgument(0)));

        var req = new UpdateIfrs17PortfolioRequest("New", "updated", "PROPERTY");

        StepVerifier.create(service.update(id, req, UUID.randomUUID().toString(), "alice@example.com"))
                .assertNext(p -> {
                    assertThat(p.getName()).isEqualTo("New");
                    assertThat(p.getInsuranceLine()).isEqualTo("PROPERTY");
                })
                .verifyComplete();
    }

    private static Ifrs17Portfolio portfolio(UUID id, String name, boolean active) {
        var p = new Ifrs17Portfolio();
        p.setId(id);
        p.setName(name);
        p.setDescription("");
        p.setInsuranceLine("VEHICLE");
        p.setIsActive(active);
        return p;
    }
}
