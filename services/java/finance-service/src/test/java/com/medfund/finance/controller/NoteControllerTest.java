package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.Note;
import com.medfund.finance.service.NoteService;
import com.medfund.finance.service.NotesExcelService;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.security.SecurityEventPublisher;
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

@WebFluxTest(NoteController.class)
@Import(SecurityConfig.class)
class NoteControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private NoteService noteService;

    // Phase 1 §B added new dependencies to NoteController for the envelope
    // wrap and the /page/export/excel endpoint. They're mocked here so the
    // WebFlux slice can spin up; only the pre-existing tests need to react.
    @MockBean
    private NotesExcelService notesExcelService;

    @MockBean
    private ReportEnvelopeBuilder reportEnvelopeBuilder;

    @MockBean
    private SecurityEventPublisher securityEventPublisher;

    @Test
    void findByProviderId_returns200() {
        UUID providerId = UUID.randomUUID();
        when(noteService.findByProviderId(providerId)).thenReturn(Flux.just(newDebitNote()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/notes/provider/{providerId}", providerId)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        when(noteService.findById(id)).thenReturn(Mono.just(newDebitNote()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/notes/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_debitNote_returns201() {
        when(noteService.create(any(), any(), any())).thenReturn(Mono.just(newDebitNote()));

        String body = """
                {
                    "direction": "DEBIT",
                    "noteType": "TAX_WITHHELD",
                    "providerId": "%s",
                    "amount": 50.00,
                    "currencyCode": "USD",
                    "reason": "Withholding tax"
                }
                """.formatted(UUID.randomUUID());

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void create_memoNote_noPayeeOk() {
        when(noteService.create(any(), any(), any())).thenReturn(Mono.just(newMemoNote()));

        String body = """
                {
                    "direction": "DEBIT",
                    "noteType": "MEMO",
                    "amount": 12.34,
                    "currencyCode": "USD",
                    "reason": "Bank fee write-off"
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void reverse_returns200() {
        UUID id = UUID.randomUUID();
        when(noteService.reverse(any(), any(), any(), any())).thenReturn(Mono.just(newDebitNote()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/notes/{id}/reverse", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"posted in error\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_returns204() {
        UUID id = UUID.randomUUID();
        when(noteService.delete(any(), any(), any())).thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .delete().uri("/api/v1/notes/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNoContent();
    }

    private Note newDebitNote() {
        var n = new Note();
        n.setId(UUID.randomUUID());
        n.setNoteNumber("DN-123456");
        n.setProviderId(UUID.randomUUID());
        n.setDirection("DEBIT");
        n.setNoteType("TAX_WITHHELD");
        n.setType("ORIGINAL");
        n.setAmount(new BigDecimal("50.00"));
        n.setCurrencyCode("USD");
        n.setReason("Withholding tax");
        n.setStatus("pending");
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        return n;
    }

    private Note newMemoNote() {
        var n = new Note();
        n.setId(UUID.randomUUID());
        n.setNoteNumber("MEMO-100001");
        n.setDirection("DEBIT");
        n.setNoteType("MEMO");
        n.setType("ORIGINAL");
        n.setAmount(new BigDecimal("12.34"));
        n.setCurrencyCode("USD");
        n.setReason("Bank fee write-off");
        n.setStatus("pending");
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        return n;
    }
}
