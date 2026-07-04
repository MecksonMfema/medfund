package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateMemberRequest;
import com.medfund.user.dto.CursorPage;
import com.medfund.user.dto.MemberResponse;
import com.medfund.user.dto.UpdateMemberRequest;
import com.medfund.user.entity.Member;
import com.medfund.user.exception.MemberNotFoundException;
import com.medfund.user.exception.DuplicateMemberException;
import com.medfund.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;
    private final KeycloakSyncService keycloakSyncService;
    private final MemberLifecycleService lifecycleService;
    private final AgeGroupResolver ageGroupResolver;
    private final MemberNumberService memberNumberService;

    public Flux<Member> findAll() {
        return memberRepository.findAllOrderByCreatedAtDesc();
    }

    /**
     * Cursor-based paginated list of members with optional search and status filter.
     * Decodes the cursor to retrieve the correct page; encodes a next cursor from the last item.
     */
    public Mono<CursorPage<MemberResponse>> findPage(String q, String status, String cursor, int limit) {
        Instant cursorTs = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            cursorTs = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            cursorId = UUID.fromString(parts[1]);
        }

        boolean hasQ = q != null && !q.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        int fetch = limit + 1; // one extra to detect hasMore

        Flux<Member> source;
        if (hasQ) {
            source = cursorTs == null
                    ? memberRepository.searchFirstPage(q, fetch)
                    : memberRepository.searchNextPage(q, cursorTs, cursorId, fetch);
        } else if (hasStatus) {
            source = cursorTs == null
                    ? memberRepository.findFirstPageByStatus(status, fetch)
                    : memberRepository.findNextPageByStatus(status, cursorTs, cursorId, fetch);
        } else {
            source = cursorTs == null
                    ? memberRepository.findFirstPage(fetch)
                    : memberRepository.findNextPage(cursorTs, cursorId, fetch);
        }

        return source.collectList().map(rows -> {
            boolean hasMore = rows.size() > limit;
            List<Member> content = hasMore ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasMore && !content.isEmpty()) {
                Member last = content.get(content.size() - 1);
                String raw = last.getCreatedAt().toEpochMilli() + ":" + last.getId();
                nextCursor = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            }
            List<MemberResponse> dto = content.stream().map(MemberResponse::from).toList();
            return new CursorPage<>(dto, nextCursor, hasMore, limit);
        });
    }

    public Mono<Member> findById(UUID id) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)));
    }

    public Mono<Member> findByMemberNumber(String memberNumber) {
        return memberRepository.findByMemberNumber(memberNumber)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(memberNumber)));
    }

    public Flux<Member> findByGroupId(UUID groupId) {
        return memberRepository.findByGroupId(groupId);
    }

    public Flux<Member> findByStatus(String status) {
        return memberRepository.findByStatus(status);
    }

    public Flux<Member> search(String query) {
        return memberRepository.search(query);
    }

    @Transactional
    public Mono<Member> enroll(CreateMemberRequest request, String actorId, String actorEmail) {
        return memberNumberService.nextMemberNumber()
            .flatMap(memberNumber -> {
                var member = new Member();
                // id NOT set — let PostgreSQL generate via DEFAULT gen_random_uuid()
                member.setMemberNumber(memberNumber);
                member.setFirstName(request.firstName());
                member.setLastName(request.lastName());
                member.setDateOfBirth(request.dateOfBirth());
                member.setGender(request.gender());
                member.setNationalId(request.nationalId());
                member.setEmail(request.email());
                member.setPhone(request.phone());
                member.setAddress(request.address());
                member.setGroupId(request.groupId());
                member.setSchemeId(request.schemeId());
                member.setStatus("enrolled");
                member.setEnrollmentDate(request.enrollmentDateOrDefault());
                member.setCreatedAt(Instant.now());
                member.setUpdatedAt(Instant.now());
                // actorId comes from the JWT subject — usually the Keycloak
                // user UUID, but not guaranteed to be parseable as a UUID
                // (sub can be a username on legacy realms). Leave null if it
                // can't be parsed; the audit pipeline keeps the original
                // actorId string for traceability.
                UUID actorUuid = safeParseUuid(actorId);
                member.setCreatedBy(actorUuid);
                member.setUpdatedBy(actorUuid);

                // Stamp the canonical age bucket from DoB against the
                // scheme's bands. If no band covers the age, ageGroupId
                // stays null and surfaces later as a billing data-gap
                // (better than silently picking the youngest/oldest band).
                return ageGroupResolver
                        .resolveForSchemeAndDob(member.getSchemeId(), member.getDateOfBirth())
                        .doOnNext(member::setAgeGroupId)
                        .then(r2dbcTemplate.insert(member));
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                String realm = "tenant-" + tenantId;

                // Sync to Keycloak
                Mono<Void> keycloakSync = Mono.empty();
                if (saved.getEmail() != null && !saved.getEmail().isBlank()) {
                    keycloakSync = keycloakSyncService.createUser(
                        realm, saved.getEmail(), saved.getFirstName(), saved.getLastName(),
                        List.of("member")
                    ).flatMap(keycloakUserId -> {
                        saved.setKeycloakUserId(keycloakUserId);
                        return memberRepository.save(saved).then();
                    }).onErrorResume(e -> {
                        log.warn("Keycloak sync failed for member {}: {}", saved.getMemberNumber(), e.getMessage());
                        return Mono.empty();
                    });
                }

                // Run AGE_GROUP / UNDERWRITING / MEMBER_LIFECYCLE rules so the
                // tenant's enrollment policy fires (loaded premiums, manual-review
                // flags, etc.). Outcomes land in the audit trail; tenants without
                // lifecycle rules see no behaviour change. Failures here MUST
                // NOT bomb the enrollment — the member is already inserted, and
                // a missing/broken Drools KieBase shouldn't roll that back.
                Mono<Void> lifecycleEval = lifecycleService.evaluateOnEnrollment(saved)
                        .doOnNext(fact -> {
                            if (fact.getResults() != null && !fact.getResults().isEmpty()) {
                                log.info("Lifecycle rules fired for new member {}: {}",
                                        saved.getMemberNumber(), fact.getResults().size());
                            }
                        })
                        .then()
                        .onErrorResume(e -> {
                            log.warn("Lifecycle rules failed for member {}: {}", saved.getMemberNumber(), e.getMessage());
                            return Mono.empty();
                        });

                // Audit + Kafka publish are also best-effort from the caller's
                // perspective — if they fail, the member is still enrolled and
                // we surface a 200 with a warning in the server log.
                Mono<Void> auditAndEvents = publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE")
                        .then(eventPublisher.publishMemberEnrolled(
                            saved.getId().toString(),
                            saved.getMemberNumber(),
                            saved.getGroupId()  != null ? saved.getGroupId().toString()  : null,
                            saved.getSchemeId() != null ? saved.getSchemeId().toString() : null,
                            saved.getEnrollmentDate() != null ? saved.getEnrollmentDate().toString() : null
                        ))
                        .onErrorResume(e -> {
                            log.warn("Audit/event publish failed for member {}: {}", saved.getMemberNumber(), e.getMessage());
                            return Mono.empty();
                        });

                return keycloakSync.then(lifecycleEval).then(auditAndEvents).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Member> update(UUID id, UpdateMemberRequest request, String actorId, String actorEmail) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyMember(existing);

                if (request.firstName() != null) existing.setFirstName(request.firstName());
                if (request.lastName() != null) existing.setLastName(request.lastName());
                if (request.gender() != null) existing.setGender(request.gender());
                if (request.nationalId() != null) existing.setNationalId(request.nationalId());
                if (request.email() != null) existing.setEmail(request.email());
                if (request.phone() != null) existing.setPhone(request.phone());
                if (request.address() != null) existing.setAddress(request.address());
                if (request.groupId() != null) existing.setGroupId(request.groupId());
                if (request.schemeId() != null) existing.setSchemeId(request.schemeId());

                // Individual-pricing override (V030). Three fields move
                // together: setting amount requires effective_from (the
                // DB CHECK enforces this — bean-validation belt-and-
                // braces below). Clearing amount also clears reason +
                // effective_from so the row doesn't carry stale context.
                applyOverride(existing, request.billingOverrideAmount(),
                        request.billingOverrideReason(),
                        request.billingOverrideEffectiveFrom());

                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));

                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Member> activate(UUID id, String actorId, String actorEmail) {
        return activate(id, null, null, actorId, actorEmail);
    }

    @Transactional
    public Mono<Member> activate(UUID id, LocalDate effectiveDate, String reason,
                                  String actorId, String actorEmail) {
        return applyOrSchedule(id, "active", effectiveDate, reason, actorId, actorEmail);
    }

    @Transactional
    public Mono<Member> suspend(UUID id, String actorId, String actorEmail) {
        return suspend(id, null, null, actorId, actorEmail);
    }

    @Transactional
    public Mono<Member> suspend(UUID id, LocalDate effectiveDate, String reason,
                                 String actorId, String actorEmail) {
        return applyOrSchedule(id, "suspended", effectiveDate, reason, actorId, actorEmail)
            .flatMap(member -> Mono.deferContextual(ctx -> {
                // Keycloak disable only fires on immediate suspensions —
                // a future-dated suspend leaves the user active in
                // Keycloak until the daily job actually flips them.
                if (!"suspended".equals(member.getStatus())) return Mono.just(member);
                String tenantId = TenantContext.get(ctx);
                if (member.getKeycloakUserId() != null) {
                    return keycloakSyncService.disableUser("tenant-" + tenantId, member.getKeycloakUserId())
                        .thenReturn(member);
                }
                return Mono.just(member);
            }));
    }

    @Transactional
    public Mono<Member> terminate(UUID id, String actorId, String actorEmail) {
        return terminate(id, null, null, actorId, actorEmail);
    }

    @Transactional
    public Mono<Member> terminate(UUID id, LocalDate effectiveDate, String reason,
                                   String actorId, String actorEmail) {
        return applyOrSchedule(id, "terminated", effectiveDate, reason, actorId, actorEmail)
            .flatMap(member -> Mono.deferContextual(ctx -> {
                if (!"terminated".equals(member.getStatus())) return Mono.just(member);
                String tenantId = TenantContext.get(ctx);
                if (member.getKeycloakUserId() != null) {
                    return keycloakSyncService.disableUser("tenant-" + tenantId, member.getKeycloakUserId())
                        .thenReturn(member);
                }
                return Mono.just(member);
            }));
    }

    @Transactional
    public Mono<Member> deactivate(UUID id, LocalDate effectiveDate, String reason,
                                    String actorId, String actorEmail) {
        return applyOrSchedule(id, "deactivated", effectiveDate, reason, actorId, actorEmail);
    }

    /**
     * Router for the four lifecycle transitions. When {@code effectiveDate}
     * is null or on/before today, applies immediately (delegates to
     * {@link #transitionStatus} so the existing MEMBER_STATUS_CHANGED
     * event fires); when it's in the future, stashes the target in the
     * {@code scheduled_status} trio for the daily
     * {@link com.medfund.shared.scheduler.JobType#SCHEDULED_STATUS_ROLL}
     * job to pick up.
     */
    private Mono<Member> applyOrSchedule(UUID id, String targetStatus, LocalDate effectiveDate,
                                          String reason, String actorId, String actorEmail) {
        LocalDate today = LocalDate.now();
        boolean isFuture = effectiveDate != null && effectiveDate.isAfter(today);
        if (!isFuture) {
            return transitionStatus(id, targetStatus, actorId, actorEmail)
                .flatMap(saved -> {
                    // Terminated rows also record the termination_date so the
                    // downstream MemberLifecycleConsumer can compute the
                    // LATE_TERMINATION_CREDIT window from it.
                    if ("terminated".equals(targetStatus) && saved.getTerminationDate() == null) {
                        saved.setTerminationDate(effectiveDate != null ? effectiveDate : today);
                        return memberRepository.save(saved);
                    }
                    return Mono.just(saved);
                });
        }
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyMember(existing);
                existing.setScheduledStatus(targetStatus);
                existing.setScheduledStatusEffectiveFrom(effectiveDate);
                existing.setScheduledStatusReason(reason);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));
                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        // Audit-only — no MEMBER_STATUS_CHANGED yet; that
                        // publishes when the scheduled roll actually
                        // flips the status.
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                                .thenReturn(saved);
                    }));
            });
    }

    private Mono<Member> transitionStatus(UUID id, String newStatus, String actorId, String actorEmail) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyMember(existing);
                existing.setStatus(newStatus);
                // Clear the scheduled trio — the roll job (or a manual
                // apply-now) has consumed the schedule.
                existing.setScheduledStatus(null);
                existing.setScheduledStatusEffectiveFrom(null);
                existing.setScheduledStatusReason(null);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));

                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .then(eventPublisher.publishMemberLifecycle(
                                saved.getId().toString(),
                                newStatus,
                                saved.getTerminationDate() != null ? saved.getTerminationDate().toString() : null,
                                saved.getGroupId()  != null ? saved.getGroupId().toString()  : null,
                                saved.getSchemeId() != null ? saved.getSchemeId().toString() : null))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * UUID.fromString throws on null / blank / non-UUID inputs. We use this
     * helper anywhere actorId hits a UUID column so a non-Keycloak JWT
     * doesn't bring down the request.
     */
    private static UUID safeParseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    // generateMemberNumber removed — MemberNumberService is now the single
    // source of truth for member-number issuance, honouring the tenant's
    // configured scheme (INDEPENDENT vs SHARED_WITH_SUFFIX).

    private Mono<Void> publishAudit(String tenantId, Member current, Member previous, String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            "Member",
            current.getId().toString(),
            current.getMemberNumber(),
            action,
            actorId,
            actorEmail,
            previous != null ? Map.of("status", previous.getStatus(), "firstName", previous.getFirstName(), "lastName", previous.getLastName()) : null,
            Map.of("status", current.getStatus(), "firstName", current.getFirstName(), "lastName", current.getLastName(), "memberNumber", current.getMemberNumber()),
            new String[]{"status", "firstName", "lastName", "email", "phone"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    /**
     * Null out the override fields so billing falls back to the
     * age-group price. Audited as a normal UPDATE so the operator
     * can see the previous override amount in the diff. No-op when
     * the member has no override set.
     */
    @Transactional
    public Mono<Member> clearBillingOverride(UUID id, String actorId, String actorEmail) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                if (existing.getBillingOverrideAmount() == null) {
                    return Mono.just(existing);
                }
                var previous = copyMember(existing);
                existing.setBillingOverrideAmount(null);
                existing.setBillingOverrideReason(null);
                existing.setBillingOverrideEffectiveFrom(null);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));
                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Apply a billing-override payload to a Member. Three-way semantics:
     * <ul>
     *   <li>amount is non-null + non-zero → set all three fields,
     *       require effective_from (DB CHECK enforces too, but throw
     *       earlier here for a clean 400 instead of a SQL error).</li>
     *   <li>amount is null in the request → no change. The existing
     *       override (if any) stays; use the explicit
     *       /clear-billing-override endpoint to remove one.</li>
     * </ul>
     */
    private static void applyOverride(Member m,
                                       java.math.BigDecimal amount,
                                       String reason,
                                       java.time.LocalDate effectiveFrom) {
        if (amount == null) return;
        if (effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "billingOverrideEffectiveFrom is required when billingOverrideAmount is set");
        }
        m.setBillingOverrideAmount(amount);
        m.setBillingOverrideReason(reason);
        m.setBillingOverrideEffectiveFrom(effectiveFrom);
    }

    private Member copyMember(Member source) {
        var copy = new Member();
        copy.setId(source.getId());
        copy.setMemberNumber(source.getMemberNumber());
        copy.setFirstName(source.getFirstName());
        copy.setLastName(source.getLastName());
        copy.setDateOfBirth(source.getDateOfBirth());
        copy.setGender(source.getGender());
        copy.setNationalId(source.getNationalId());
        copy.setEmail(source.getEmail());
        copy.setPhone(source.getPhone());
        copy.setAddress(source.getAddress());
        copy.setGroupId(source.getGroupId());
        copy.setSchemeId(source.getSchemeId());
        copy.setKeycloakUserId(source.getKeycloakUserId());
        copy.setStatus(source.getStatus());
        copy.setEnrollmentDate(source.getEnrollmentDate());
        copy.setTerminationDate(source.getTerminationDate());
        return copy;
    }
}
