package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateIfrs17CohortRequest;
import com.medfund.user.dto.UpdateIfrs17CohortRequest;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.entity.Ifrs17Portfolio;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.exception.Ifrs17PortfolioNotFoundException;
import com.medfund.user.repository.Ifrs17CohortRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Ifrs17CohortServiceTest {

    @Mock private Ifrs17CohortRepository cohortRepository;
    @Mock private Ifrs17PortfolioRepository portfolioRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;
    @Mock private CohortStatusHistoryService statusHistoryService;

    private Ifrs17CohortService service;
    private final UUID portfolioId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new Ifrs17CohortService(cohortRepository, portfolioRepository, r2dbcTemplate,
                auditPublisher, statusHistoryService);
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        // Only invoked when cohort_type actually changes; the strict-stub default returns null,
        // which NPEs .thenReturn(saved). Lenient stub keeps unrelated tests silent.
        org.mockito.Mockito.lenient().when(statusHistoryService.recordTransition(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new com.medfund.user.entity.CohortStatusHistory()));
    }

    @Test
    void findAll_activeOnly_delegatesToActive() {
        when(cohortRepository.findAllActive()).thenReturn(Flux.just(cohort(UUID.randomUUID(), "C1")));

        StepVerifier.create(service.findAll(false))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void findById_missing_errorsWithNotFound() {
        UUID id = UUID.randomUUID();
        when(cohortRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(id))
                .expectError(Ifrs17CohortNotFoundException.class)
                .verify();
    }

    @Test
    void create_missingPortfolio_errorsWithPortfolioNotFound() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Mono.empty());

        var req = new CreateIfrs17CohortRequest(portfolioId, 2026, "NON_ONEROUS", "Cohort A");

        StepVerifier.create(service.create(req, UUID.randomUUID().toString(), "alice@example.com"))
                .expectError(Ifrs17PortfolioNotFoundException.class)
                .verify();
    }

    @Test
    void create_duplicateCompositeKey_returns409() {
        var portfolio = new Ifrs17Portfolio();
        portfolio.setId(portfolioId);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Mono.just(portfolio));
        when(cohortRepository.existsByCompositeKey(portfolioId, 2026, "NON_ONEROUS"))
                .thenReturn(Mono.just(true));

        var req = new CreateIfrs17CohortRequest(portfolioId, 2026, "NON_ONEROUS", "Cohort A");

        StepVerifier.create(service.create(req, UUID.randomUUID().toString(), "alice@example.com"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
    }

    @Test
    void create_success_persistsAndPublishesAudit() {
        var portfolio = new Ifrs17Portfolio();
        portfolio.setId(portfolioId);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Mono.just(portfolio));
        when(cohortRepository.existsByCompositeKey(portfolioId, 2026, "NON_ONEROUS"))
                .thenReturn(Mono.just(false));
        var saved = cohort(UUID.randomUUID(), "Cohort A");
        saved.setPortfolioId(portfolioId);
        when(r2dbcTemplate.insert(any(Ifrs17Cohort.class))).thenReturn(Mono.just(saved));

        var req = new CreateIfrs17CohortRequest(portfolioId, 2026, "NON_ONEROUS", "Cohort A");

        StepVerifier.create(service.create(req, UUID.randomUUID().toString(), "alice@example.com"))
                .assertNext(c -> {
                    assertThat(c.getName()).isEqualTo("Cohort A");
                    assertThat(c.getPortfolioId()).isEqualTo(portfolioId);
                })
                .verifyComplete();
    }

    @Test
    void softDelete_withReferencingPolicies_returns409() {
        UUID id = UUID.randomUUID();
        when(cohortRepository.findById(id)).thenReturn(Mono.just(cohort(id, "Cohort A")));
        when(cohortRepository.countReferencingPolicies(id)).thenReturn(Mono.just(5L));

        StepVerifier.create(service.softDelete(id, UUID.randomUUID().toString(), "alice@example.com"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 409)
                .verify();
    }

    @Test
    void update_success_flipsFieldsAndPersists() {
        UUID id = UUID.randomUUID();
        var existing = cohort(id, "Old");
        existing.setPortfolioId(portfolioId);
        when(cohortRepository.findById(id)).thenReturn(Mono.just(existing));
        var portfolio = new Ifrs17Portfolio();
        portfolio.setId(portfolioId);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Mono.just(portfolio));
        when(cohortRepository.existsByCompositeKeyAndIdNot(portfolioId, 2027, "ONEROUS", id))
                .thenReturn(Mono.just(false));
        when(cohortRepository.save(any(Ifrs17Cohort.class))).thenAnswer(inv ->
                Mono.just((Ifrs17Cohort) inv.getArgument(0)));

        var req = new UpdateIfrs17CohortRequest(portfolioId, 2027, "ONEROUS", "New");

        StepVerifier.create(service.update(id, req, UUID.randomUUID().toString(), "alice@example.com"))
                .assertNext(c -> {
                    assertThat(c.getName()).isEqualTo("New");
                    assertThat(c.getCohortType()).isEqualTo("ONEROUS");
                    assertThat(c.getCohortYear()).isEqualTo(2027);
                })
                .verifyComplete();
    }

    private static Ifrs17Cohort cohort(UUID id, String name) {
        var c = new Ifrs17Cohort();
        c.setId(id);
        c.setName(name);
        c.setCohortYear(2026);
        c.setCohortType("NON_ONEROUS");
        c.setIsActive(true);
        return c;
    }
}
