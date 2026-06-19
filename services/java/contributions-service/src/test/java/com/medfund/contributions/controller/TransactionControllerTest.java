package com.medfund.contributions.controller;

import com.medfund.contributions.config.SecurityConfig;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.service.TransactionService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TransactionService transactionService;

    @Test
    void search_returns200() {
        when(transactionService.search(any())).thenReturn(Mono.just(
                PageResponse.of(java.util.List.of(createTestTransaction()), 1L, 0, 20)));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/transactions")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void record_returns201() {
        when(transactionService.record(any(), any(), any())).thenReturn(Mono.just(createTestTransaction()));

        String body = """
                {
                    "contributionId": "%s",
                    "amount": 250.00,
                    "currencyCode": "USD",
                    "transactionType": "payment",
                    "paymentMethod": "bank_transfer",
                    "reference": "REF-001"
                }
                """.formatted(UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Transaction createTestTransaction() {
        var t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setTransactionNumber("TXN-000001");
        t.setAmount(new BigDecimal("250.00"));
        t.setCurrencyCode("USD");
        t.setTransactionType("payment");
        t.setPaymentMethod("bank_transfer");
        t.setStatus("completed");
        t.setTransactionDate(Instant.now());
        t.setCreatedAt(Instant.now());
        return t;
    }
}
