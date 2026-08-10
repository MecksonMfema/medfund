package com.medfund.finance.service;

import com.medfund.finance.dto.CreateNoteRequest;
import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.entity.Note;
import com.medfund.finance.exception.NoteNotFoundException;
import com.medfund.finance.repository.NoteQueryRepository;
import com.medfund.finance.repository.NoteRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private static final String MEMO = "MEMO";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private final NoteRepository noteRepository;
    private final NoteQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;

    public Flux<Note> findByProviderId(UUID providerId) {
        return noteRepository.findByProviderId(providerId);
    }

    public Flux<Note> findByMemberId(UUID memberId) {
        return noteRepository.findByMemberId(memberId);
    }

    public Flux<Note> findByStatus(String status) {
        return noteRepository.findByStatus(status);
    }

    /**
     * Server-side paginated notes list. Feeds the /debit-notes,
     * /credit-notes, and /notes routes; the front-end pins
     * {@code direction} when it wants a single-direction view.
     */
    public Mono<PageResponse<NoteRow>> searchPaged(NoteFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Mono<Note> findById(UUID id) {
        return noteRepository.findById(id)
            .switchIfEmpty(Mono.error(new NoteNotFoundException(id)));
    }

    @Transactional
    public Mono<Note> create(CreateNoteRequest request, String actorId, String actorEmail) {
        String direction = normalise(request.direction());
        String noteType  = normaliseType(request.noteType());

        if (!DEBIT.equals(direction) && !CREDIT.equals(direction)) {
            return Mono.error(new IllegalArgumentException("direction must be DEBIT or CREDIT"));
        }
        // Payee required unless MEMO (matches the DB CHECK).
        if (!MEMO.equals(noteType)
                && request.providerId() == null
                && request.memberId() == null) {
            return Mono.error(new IllegalArgumentException(
                "providerId or memberId is required unless noteType='MEMO'"));
        }

        return generateNoteNumber(direction, noteType)
            .flatMap(noteNumber -> {
                var note = new Note();
                note.setNoteNumber(noteNumber);
                note.setProviderId(MEMO.equals(noteType) ? null : request.providerId());
                note.setMemberId(MEMO.equals(noteType) ? null : request.memberId());
                note.setDirection(direction);
                note.setNoteType(noteType);
                note.setType("ORIGINAL");
                note.setAmount(request.amount());
                note.setCurrencyCode(request.currencyCode());
                note.setReason(request.reason());
                note.setStatus("pending");
                note.setCreatedAt(Instant.now());
                note.setUpdatedAt(Instant.now());
                note.setCreatedBy(Actors.parseId(actorId));
                return noteRepository.save(note);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, saved.getId().toString(), saved.getNoteNumber(),
                        "CREATE", actorId, actorEmail,
                        null, snapshot(saved))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Note> approve(UUID id, String actorId, String actorEmail) {
        return noteRepository.findById(id)
            .switchIfEmpty(Mono.error(new NoteNotFoundException(id)))
            .flatMap(note -> {
                String previousStatus = note.getStatus();
                note.setStatus("approved");
                note.setApprovedBy(Actors.parseId(actorId));
                note.setApprovedAt(Instant.now());
                note.setUpdatedAt(Instant.now());
                return noteRepository.save(note)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved.getId().toString(), saved.getNoteNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Approved → applied. Stamps {@code posted_at=now()} — this is the
     * timestamp advice-generation slots the note into a run window
     * against. Fires {@code publishNoteApplied} for downstream fanout.
     */
    @Transactional
    public Mono<Note> apply(UUID id, String actorId, String actorEmail) {
        return noteRepository.findById(id)
            .switchIfEmpty(Mono.error(new NoteNotFoundException(id)))
            .flatMap(note -> {
                String previousStatus = note.getStatus();
                Instant now = Instant.now();
                note.setStatus("applied");
                note.setPostedAt(now);
                note.setUpdatedAt(now);
                return noteRepository.save(note)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved.getId().toString(), saved.getNoteNumber(),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(),
                                       "postedAt", saved.getPostedAt().toString()))
                            .then(eventPublisher.publishNoteApplied(
                                saved.getId().toString(),
                                saved.getDirection(),
                                saved.getNoteType(),
                                saved.getAmount().toString()))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Compensating-entry reversal. Inserts a REVERSAL row with opposite
     * direction and flips the original to {@code status='reversed'} in
     * the same transaction. Same pattern as
     * {@link AdvancePaymentService#reverse} and
     * {@link CtcPaymentService} — a REVERSAL is itself immutable and
     * cannot be reversed further.
     *
     * @return the compensating REVERSAL row.
     */
    @Transactional
    public Mono<Note> reverse(UUID originalId, String reason, String actorId, String actorEmail) {
        return noteRepository.findById(originalId)
            .switchIfEmpty(Mono.error(new NoteNotFoundException(originalId)))
            .flatMap(original -> {
                if (!"applied".equals(original.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Only applied notes can be reversed (this note is " + original.getStatus() + ")"));
                }
                if ("REVERSAL".equals(original.getType())) {
                    return Mono.error(new IllegalStateException(
                        "Cannot reverse a compensating REVERSAL row"));
                }
                Instant now = Instant.now();
                UUID actorUuid = Actors.parseId(actorId);
                String previousStatus = original.getStatus();
                original.setStatus("reversed");
                original.setUpdatedAt(now);

                String oppositeDirection = DEBIT.equals(original.getDirection()) ? CREDIT : DEBIT;
                return generateNoteNumber(oppositeDirection, original.getNoteType())
                    .flatMap(compensatingNumber -> {
                        var compensating = new Note();
                        compensating.setNoteNumber(compensatingNumber);
                        compensating.setProviderId(original.getProviderId());
                        compensating.setMemberId(original.getMemberId());
                        compensating.setDirection(oppositeDirection);
                        compensating.setNoteType(original.getNoteType());
                        compensating.setType("REVERSAL");
                        compensating.setReversesNoteId(original.getId());
                        compensating.setAmount(original.getAmount());
                        compensating.setCurrencyCode(original.getCurrencyCode());
                        compensating.setReason(reason != null && !reason.isBlank()
                                ? reason
                                : ("Reversal of " + original.getNoteNumber()));
                        compensating.setStatus("applied");
                        compensating.setApprovedBy(actorUuid);
                        compensating.setApprovedAt(now);
                        compensating.setPostedAt(now);
                        compensating.setCreatedAt(now);
                        compensating.setUpdatedAt(now);
                        compensating.setCreatedBy(actorUuid);

                        return noteRepository.save(original)
                            .then(noteRepository.save(compensating))
                            .flatMap(savedCompensating -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit(tenantId,
                                            original.getId().toString(), original.getNoteNumber(),
                                            "UPDATE", actorId, actorEmail,
                                            Map.of("status", previousStatus),
                                            Map.of("status", original.getStatus()))
                                    .then(publishAudit(tenantId,
                                            savedCompensating.getId().toString(),
                                            savedCompensating.getNoteNumber(),
                                            "CREATE", actorId, actorEmail,
                                            null, snapshot(savedCompensating)))
                                    .then(eventPublisher.publishNoteReversed(
                                            original.getId().toString(),
                                            savedCompensating.getId().toString(),
                                            savedCompensating.getAmount().toString(),
                                            savedCompensating.getCurrencyCode()))
                                    .thenReturn(savedCompensating);
                            }));
                    });
            });
    }

    /**
     * Hard-delete for pending / approved notes. Applied notes must go
     * through {@link #reverse} instead — the endpoint returns 422 in
     * that case.
     */
    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return noteRepository.findById(id)
            .switchIfEmpty(Mono.error(new NoteNotFoundException(id)))
            .flatMap(note -> {
                if ("applied".equals(note.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Cannot delete an applied note — post a reversal via /reverse instead"));
                }
                if ("reversed".equals(note.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Cannot delete a reversed note"));
                }
                return noteRepository.delete(note)
                    .then(Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, note.getId().toString(), note.getNoteNumber(),
                                "DELETE", actorId, actorEmail,
                                snapshot(note), null);
                    }));
            });
    }

    // ---- Private helpers -------------------------------------------------

    private Mono<String> generateNoteNumber(String direction, String noteType) {
        String prefix = MEMO.equals(noteType) ? "MEMO"
                      : DEBIT.equals(direction) ? "DN"
                      : "CN";
        String number = prefix + "-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return noteRepository.existsByNoteNumber(number)
            .flatMap(exists -> exists ? generateNoteNumber(direction, noteType) : Mono.just(number));
    }

    private static String normalise(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String normaliseType(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static Map<String, Object> snapshot(Note n) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("noteNumber", n.getNoteNumber());
        snap.put("direction", n.getDirection());
        snap.put("noteType", n.getNoteType());
        snap.put("type", n.getType());
        snap.put("amount", n.getAmount().toPlainString());
        snap.put("currencyCode", n.getCurrencyCode());
        snap.put("status", n.getStatus());
        snap.put("providerId", n.getProviderId() != null ? n.getProviderId().toString() : null);
        snap.put("memberId", n.getMemberId() != null ? n.getMemberId().toString() : null);
        snap.put("reversesNoteId", n.getReversesNoteId() != null ? n.getReversesNoteId().toString() : null);
        return snap;
    }

    private Mono<Void> publishAudit(String tenantId, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            "Note",
            entityId,
            entityName,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
