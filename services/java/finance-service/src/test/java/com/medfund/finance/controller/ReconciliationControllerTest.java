package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.BankReconciliation;
import com.medfund.finance.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(ReconciliationController.class)
@Import(SecurityConfig.class)
class ReconciliationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ReconciliationService reconciliationService;

    @Test
    void findAll_returns200() {
        when(reconciliationService.findAll()).thenReturn(Flux.just(createTestReconciliation()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/reconciliations")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(reconciliationService.create(any(), any(), any())).thenReturn(Mono.just(createTestReconciliation()));

        String body = """
                {
                    "referenceNumber": "STMT-2026-001",
                    "statementAmount": 10000.00,
                    "systemAmount": 10000.00,
                    "currencyCode": "USD",
                    "statementDate": "2026-01-31"
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/reconciliations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private BankReconciliation createTestReconciliation() {
        var r = new BankReconciliation();
        r.setId(UUID.randomUUID());
        r.setReferenceNumber("STMT-2026-001");
        r.setStatementAmount(new BigDecimal("10000.00"));
        r.setSystemAmount(new BigDecimal("10000.00"));
        r.setDifference(BigDecimal.ZERO);
        r.setCurrencyCode("USD");
        r.setStatus("matched");
        r.setStatementDate(LocalDate.of(2026, 1, 31));
        r.setCreatedAt(Instant.now());
        return r;
    }
}
