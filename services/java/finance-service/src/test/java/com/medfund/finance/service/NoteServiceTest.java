package com.medfund.finance.service;

import com.medfund.finance.dto.CreateNoteRequest;
import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteRow;
import com.medfund.finance.entity.Note;
import com.medfund.finance.exception.NoteNotFoundException;
import com.medfund.finance.repository.NoteQueryRepository;
import com.medfund.finance.repository.NoteRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    @Mock private NoteRepository noteRepository;
    @Mock private NoteQueryRepository queryRepository;
    @Mock private AuditPublisher auditPublisher;
    @Mock private FinanceEventPublisher eventPublisher;

    @InjectMocks private NoteService noteService;

    // ── findById ─────────────────────────────────────────────────────

    @Test
    void findById_existing_returnsNote() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));

        StepVerifier.create(noteService.findById(note.getId()))
                .assertNext(result -> {
                    assertThat(result.getId()).isEqualTo(note.getId());
                    assertThat(result.getNoteNumber()).isEqualTo("DN-123456");
                    assertThat(result.getStatus()).isEqualTo("pending");
                })
                .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();
        when(noteRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(noteService.findById(id))
                .expectError(NoteNotFoundException.class)
                .verify();
    }

    // ── create ───────────────────────────────────────────────────────

    @Test
    void create_debitNote_populatesCreatedByAndDNPrefix() {
        var request = new CreateNoteRequest(
                "DEBIT", "TAX_WITHHELD",
                UUID.randomUUID(), null,
                new BigDecimal("50.00"), "USD",
                "Withholding tax"
        );
        String actorId = UUID.randomUUID().toString();

        when(noteRepository.existsByNoteNumber(any())).thenReturn(Mono.just(false));
        when(noteRepository.save(any())).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return Mono.just(n);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getNoteNumber()).startsWith("DN-");
                    assertThat(saved.getDirection()).isEqualTo("DEBIT");
                    assertThat(saved.getNoteType()).isEqualTo("TAX_WITHHELD");
                    assertThat(saved.getType()).isEqualTo("ORIGINAL");
                    assertThat(saved.getStatus()).isEqualTo("pending");
                    assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
                    // Hygiene defect from research doc: createdBy MUST land on create.
                    assertThat(saved.getCreatedBy()).isNotNull();
                })
                .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void create_creditNote_getsCNPrefix() {
        var request = new CreateNoteRequest(
                "CREDIT", "GOODWILL",
                null, UUID.randomUUID(),
                new BigDecimal("25.00"), "USD",
                "Goodwill credit"
        );

        when(noteRepository.existsByNoteNumber(any())).thenReturn(Mono.just(false));
        when(noteRepository.save(any())).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return Mono.just(n);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getNoteNumber()).startsWith("CN-"))
                .verifyComplete();
    }

    @Test
    void create_memoNote_getsMEMOPrefixAndNoPayee() {
        var request = new CreateNoteRequest(
                "DEBIT", "MEMO",
                UUID.randomUUID(), null,   // payee provided but MEMO strips it
                new BigDecimal("12.34"), "USD",
                "Bank fee write-off"
        );

        when(noteRepository.existsByNoteNumber(any())).thenReturn(Mono.just(false));
        when(noteRepository.save(any())).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return Mono.just(n);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getNoteNumber()).startsWith("MEMO-");
                    assertThat(saved.getNoteType()).isEqualTo("MEMO");
                    assertThat(saved.getProviderId()).isNull();
                    assertThat(saved.getMemberId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void create_nonMemoWithoutPayee_errors() {
        var request = new CreateNoteRequest(
                "DEBIT", "TAX_WITHHELD",
                null, null,
                new BigDecimal("50.00"), "USD",
                "no payee"
        );

        StepVerifier.create(
                noteService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(noteRepository, never()).save(any());
    }

    @Test
    void create_invalidDirection_errors() {
        var request = new CreateNoteRequest(
                "SIDEWAYS", "TAX_WITHHELD",
                UUID.randomUUID(), null,
                new BigDecimal("50.00"), "USD",
                "n/a"
        );

        StepVerifier.create(
                noteService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── approve / apply ──────────────────────────────────────────────

    @Test
    void approve_pending_flipsToApproved() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));
        when(noteRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.approve(note.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("approved");
                    assertThat(saved.getApprovedBy()).isNotNull();
                    assertThat(saved.getApprovedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void apply_approved_stampsPostedAtAndFiresEvent() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        note.setStatus("approved");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));
        when(noteRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishNoteApplied(any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.apply(note.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("applied");
                    assertThat(saved.getPostedAt()).isNotNull();
                })
                .verifyComplete();

        verify(eventPublisher).publishNoteApplied(any(), eq("DEBIT"), eq("TAX_WITHHELD"), any());
    }

    // ── reverse ──────────────────────────────────────────────────────

    @Test
    void reverse_applied_insertsCompensatingRow_flipsOriginal() {
        var original = seedNote("DN-123456", "DEBIT", "PROVIDER_OVERPAYMENT_RECOVERY");
        original.setStatus("applied");
        original.setPostedAt(Instant.now());
        original.setAmount(new BigDecimal("100.00"));
        original.setProviderId(UUID.randomUUID());

        when(noteRepository.findById(original.getId())).thenReturn(Mono.just(original));
        when(noteRepository.existsByNoteNumber(any())).thenReturn(Mono.just(false));
        when(noteRepository.save(any())).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            if (n.getId() == null) n.setId(UUID.randomUUID());
            return Mono.just(n);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishNoteReversed(any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.reverse(original.getId(), "posted in error",
                                    UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(compensating -> {
                    // Compensating row is opposite direction, REVERSAL type,
                    // same amount + payee, status=applied, links back to
                    // original via reversesNoteId.
                    assertThat(compensating.getDirection()).isEqualTo("CREDIT");
                    assertThat(compensating.getType()).isEqualTo("REVERSAL");
                    assertThat(compensating.getReversesNoteId()).isEqualTo(original.getId());
                    assertThat(compensating.getStatus()).isEqualTo("applied");
                    assertThat(compensating.getPostedAt()).isNotNull();
                    assertThat(compensating.getNoteType()).isEqualTo(original.getNoteType());
                    assertThat(compensating.getAmount()).isEqualByComparingTo("100.00");
                    assertThat(compensating.getProviderId()).isEqualTo(original.getProviderId());
                    // Original was flipped in the same transaction.
                    assertThat(original.getStatus()).isEqualTo("reversed");
                })
                .verifyComplete();

        // Two audit events fired: UPDATE on original + CREATE on the compensating row.
        verify(auditPublisher, org.mockito.Mockito.times(2)).publish(any());
        verify(eventPublisher).publishNoteReversed(any(), any(), any(), any());
    }

    @Test
    void reverse_pending_errors() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        note.setStatus("pending");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));

        StepVerifier.create(
                noteService.reverse(note.getId(), null,
                                    UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void reverse_alreadyReversalRow_errors() {
        var note = seedNote("CN-123456", "CREDIT", "TAX_WITHHELD");
        note.setStatus("applied");
        note.setType("REVERSAL");
        note.setReversesNoteId(UUID.randomUUID());
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));

        StepVerifier.create(
                noteService.reverse(note.getId(), null,
                                    UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── delete ───────────────────────────────────────────────────────

    @Test
    void delete_pending_deletes() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        note.setStatus("pending");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));
        when(noteRepository.delete(any())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                noteService.delete(note.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .verifyComplete();

        verify(noteRepository).delete(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void delete_applied_errors() {
        var note = seedNote("DN-123456", "DEBIT", "TAX_WITHHELD");
        note.setStatus("applied");
        when(noteRepository.findById(note.getId())).thenReturn(Mono.just(note));

        StepVerifier.create(
                noteService.delete(note.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(noteRepository, never()).delete(any());
    }

    // ── searchPaged — envelope + clamp contract ─────────────────────

    @Test
    void searchPaged_wrapsQueryRepoRowsInPageResponse() {
        var row = new NoteRow(
                UUID.randomUUID(), "DN-000001",
                UUID.randomUUID(), "Harare Clinic",
                UUID.randomUUID(), "Alice Ndlovu", "MBR-000001",
                "DEBIT", "TAX_WITHHELD", "ORIGINAL", null,
                new BigDecimal("50.00"), "USD",
                "Withholding tax", "pending",
                null, null, null,
                Instant.now(), Instant.now(), UUID.randomUUID());
        var params = new NoteFilterParams(
                null, "DEBIT", "TAX_WITHHELD", null, null, null, null,
                "createdAt", "desc", 0, 50);

        when(queryRepository.search(any(), eq(50), eq(0)))
                .thenReturn(Flux.just(row));
        when(queryRepository.count(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(noteService.searchPaged(params))
                .assertNext(resp -> {
                    assertThat(resp.content()).containsExactly(row);
                    assertThat(resp.total()).isEqualTo(1L);
                    assertThat(resp.page()).isZero();
                    assertThat(resp.size()).isEqualTo(50);
                    assertThat(resp.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void searchPaged_clampsSizeAndPage() {
        var params = new NoteFilterParams(
                null, null, null, null, null, null, null,
                "createdAt", "desc", -3, 99999);

        when(queryRepository.search(any(), eq(200), eq(0)))
                .thenReturn(Flux.empty());
        when(queryRepository.count(any())).thenReturn(Mono.just(0L));

        StepVerifier.create(noteService.searchPaged(params))
                .assertNext(resp -> {
                    assertThat(resp.page()).isZero();
                    assertThat(resp.size()).isEqualTo(200);
                })
                .verifyComplete();
    }

    // ── helper ───────────────────────────────────────────────────────

    private Note seedNote(String number, String direction, String noteType) {
        var n = new Note();
        n.setId(UUID.randomUUID());
        n.setNoteNumber(number);
        n.setProviderId(UUID.randomUUID());
        n.setDirection(direction);
        n.setNoteType(noteType);
        n.setType("ORIGINAL");
        n.setAmount(new BigDecimal("50.00"));
        n.setCurrencyCode("USD");
        n.setReason("test");
        n.setStatus("pending");
        n.setCreatedAt(Instant.now());
        n.setUpdatedAt(Instant.now());
        n.setCreatedBy(UUID.randomUUID());
        return n;
    }
}
