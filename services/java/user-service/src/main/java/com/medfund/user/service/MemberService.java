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
    public Mono<Member> enroll(CreateMemberRequest request, String actorId) {
        return generateMemberNumber()
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

                return r2dbcTemplate.insert(member);
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
                Mono<Void> auditAndEvents = publishAudit(tenantId, saved, null, actorId, "CREATE")
                        .then(eventPublisher.publishMemberEnrolled(
                            saved.getId().toString(),
                            saved.getMemberNumber(),
                            saved.getGroupId() != null ? saved.getGroupId().toString() : null
                        ))
                        .onErrorResume(e -> {
                            log.warn("Audit/event publish failed for member {}: {}", saved.getMemberNumber(), e.getMessage());
                            return Mono.empty();
                        });

                return keycloakSync.then(lifecycleEval).then(auditAndEvents).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Member> update(UUID id, UpdateMemberRequest request, String actorId) {
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
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));

                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Member> activate(UUID id, String actorId) {
        return transitionStatus(id, "active", actorId);
    }

    @Transactional
    public Mono<Member> suspend(UUID id, String actorId) {
        return transitionStatus(id, "suspended", actorId)
            .flatMap(member -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                if (member.getKeycloakUserId() != null) {
                    return keycloakSyncService.disableUser("tenant-" + tenantId, member.getKeycloakUserId())
                        .thenReturn(member);
                }
                return Mono.just(member);
            }));
    }

    @Transactional
    public Mono<Member> terminate(UUID id, String actorId) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyMember(existing);
                existing.setStatus("terminated");
                existing.setTerminationDate(LocalDate.now());
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));

                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        Mono<Void> keycloakDisable = Mono.empty();
                        if (saved.getKeycloakUserId() != null) {
                            keycloakDisable = keycloakSyncService.disableUser(
                                "tenant-" + tenantId, saved.getKeycloakUserId());
                        }
                        return keycloakDisable
                            .then(publishAudit(tenantId, saved, previous, actorId, "UPDATE"))
                            .then(eventPublisher.publishMemberLifecycle(saved.getId().toString(), "terminated"))
                            .thenReturn(saved);
                    }));
            });
    }

    private Mono<Member> transitionStatus(UUID id, String newStatus, String actorId) {
        return memberRepository.findById(id)
            .switchIfEmpty(Mono.error(new MemberNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyMember(existing);
                existing.setStatus(newStatus);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(safeParseUuid(actorId));

                return memberRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, "UPDATE")
                            .then(eventPublisher.publishMemberLifecycle(saved.getId().toString(), newStatus))
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

    private Mono<String> generateMemberNumber() {
        String number = "MBR-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return memberRepository.existsByMemberNumber(number)
            .flatMap(exists -> exists ? generateMemberNumber() : Mono.just(number));
    }

    private Mono<Void> publishAudit(String tenantId, Member current, Member previous, String actorId, String action) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            "Member",
            current.getId().toString(),
            current.getMemberNumber(),
            action,
            actorId,
            null,
            previous != null ? Map.of("status", previous.getStatus(), "firstName", previous.getFirstName(), "lastName", previous.getLastName()) : null,
            Map.of("status", current.getStatus(), "firstName", current.getFirstName(), "lastName", current.getLastName(), "memberNumber", current.getMemberNumber()),
            new String[]{"status", "firstName", "lastName", "email", "phone"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
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
