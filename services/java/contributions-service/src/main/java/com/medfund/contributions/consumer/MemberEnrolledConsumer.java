package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.BeneficiaryBenefitSeeder;
import com.medfund.contributions.service.LateAdjustmentService;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

/**
 * Consumes {@code medfund.users.member-enrolled} and auto-posts a
 * {@code LATE_ENROLMENT_CHARGE} when the new member's enrolment date
 * falls in a billing period the system has already committed for their
 * scheme.
 *
 * <p>The scenario: billing for July was committed last week, then a
 * member is enrolled with {@code enrollment_date=2026-07-01}. The
 * regular {@code BILLING_CYCLE} scheduled job won't cover them until
 * August's run — but they owe for July. This consumer detects that
 * gap and asks {@link LateAdjustmentService} to bring the ledger in
 * line for the missed month(s).
 *
 * <p>No-op paths:
 * <ul>
 *   <li>Enrolment date missing (legacy event without the enriched field).
 *   <li>Enrolment date in the current month or the future — nothing was
 *       mis-billed because the normal cycle covers the current period.
 *   <li>Scheme lookup fails (deleted / cross-tenant leak).
 *   <li>Zero prior periods have been billed for the scheme.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEnrolledConsumer {

    private static final String TOPIC = "medfund.users.member-enrolled";
    // Guard against runaway walks — no tenant should have more than a year
    // of prior periods to backfill in one shot.
    private static final int MAX_MONTHS_TO_WALK = 12;

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final ContributionRepository contributionRepository;
    private final SchemeRepository schemeRepository;
    private final LateAdjustmentService lateAdjustmentService;
    private final BeneficiaryBenefitSeeder beneficiaryBenefitSeeder;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            // Log the failure but still ack — a broken event
                            // shouldn't block the whole partition. If the
                            // domain event is important the source system's
                            // retry / operator intervention will re-emit.
                            log.warn("MemberEnrolled consumer failed for record, acking anyway: {}",
                                    e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("MemberEnrolled consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String memberIdStr = node.get("memberId").asText();
            String groupIdStr  = optText(node, "groupId");
            String schemeIdStr = optText(node, "schemeId");
            String enrollDate  = optText(node, "enrollmentDate");
            String tenantId    = optText(node, "tenantId"); // present on some upstream events; may be null
            if (schemeIdStr == null || enrollDate == null) {
                log.debug("MemberEnrolled event missing schemeId/enrollmentDate — skipping late-adjustment check");
                return Mono.empty();
            }
            UUID memberId = UUID.fromString(memberIdStr);
            UUID groupId  = groupIdStr != null ? UUID.fromString(groupIdStr) : null;
            UUID schemeId = UUID.fromString(schemeIdStr);
            LocalDate enrollment = LocalDate.parse(enrollDate);
            String dobStr = optText(node, "dateOfBirth");
            LocalDate dateOfBirth = dobStr != null ? LocalDate.parse(dobStr) : null;
            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
            // V061: seed the beneficiary_benefits ledger for the new member.
            // Runs regardless of whether the enrolment is in a past or
            // current period — the seeder only cares about schemeId +
            // enrolmentDate.
            Mono<Void> seed = beneficiaryBenefitSeeder.seed(memberId, null, schemeId,
                                                            enrollment, dateOfBirth);
            // Skip only strictly-future enrolments — the regular
            // billing cycle covers them. Current-month enrolments still
            // need the arrears check because the tenant may already
            // have committed this cycle's contributions before the
            // new member existed (V048).
            Mono<Void> lateCharge = enrollment.isAfter(currentMonth)
                    ? Mono.empty()
                    : maybePostLateEnrolment(memberId, groupId, schemeId, enrollment);
            Mono<Void> work = seed.then(lateCharge);
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse MemberEnrolled event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> maybePostLateEnrolment(UUID memberId, UUID groupId,
                                               UUID schemeId, LocalDate enrollment) {
        return schemeRepository.findById(schemeId)
                .flatMap(scheme -> countArrearsMonths(enrollment, scheme)
                        .flatMap(months -> months <= 0
                                ? Mono.<Void>empty()
                                : lateAdjustmentService.postAggregate(
                                        memberId, groupId, schemeId,
                                        enrollment, months,
                                        currencyOf(scheme),
                                        "LATE_ENROLMENT_CHARGE",
                                        memberId.toString())))
                .then();
    }

    /**
     * Count months the new member owes premium for but wasn't included
     * in a billing run. Two components:
     *
     * <ul>
     *   <li><b>Past complete months</b> — every month strictly before
     *       the current month, capped at {@link #MAX_MONTHS_TO_WALK}.
     *       Charged unconditionally: cover exists from enrolment_date,
     *       so premium accrues regardless of tenant billing history.</li>
     *   <li><b>Committed cycles from currentMonth forward</b> — the
     *       tenant may pre-bill (e.g. run July's cycle while still in
     *       June, or run August's during July). Walk from currentMonth
     *       through the {@link #MAX_MONTHS_TO_WALK} window and count
     *       every month whose contributions row set is non-empty for
     *       the scheme's line. Each committed month is one the new
     *       member missed on the batch. Non-contiguous OK: if July
     *       is empty and August is committed, August still counts.</li>
     * </ul>
     */
    private Mono<Integer> countArrearsMonths(LocalDate enrollment, Scheme scheme) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate enrollmentMonth = enrollment.withDayOfMonth(1);
        long pastMonths = Math.max(0, Math.min(
                java.time.temporal.ChronoUnit.MONTHS.between(enrollmentMonth, currentMonth),
                MAX_MONTHS_TO_WALK));

        // Only walk the forward window when the enrolment starts on
        // or before the current cycle. A strictly-future enrolment is
        // covered by the next regular cycle, no arrears needed.
        if (enrollmentMonth.isAfter(currentMonth)) {
            return Mono.just((int) pastMonths);
        }
        String line = scheme.getInsuranceLine() != null ? scheme.getInsuranceLine() : "HEALTH";
        return Flux.range(0, MAX_MONTHS_TO_WALK)
                .map(i -> currentMonth.plusMonths(i))
                .concatMap(m -> contributionRepository
                        .countByPeriodAndLine(m, m.withDayOfMonth(m.lengthOfMonth()), line)
                        .map(c -> c != null && c > 0L)
                        .defaultIfEmpty(false))
                .filter(billed -> billed)
                .count()
                .map(committedMonths -> (int) pastMonths + committedMonths.intValue());
    }

    private static String currencyOf(Scheme scheme) {
        return scheme.getCurrencyCode() != null ? scheme.getCurrencyCode() : "USD";
    }

    private static String optText(JsonNode node, String field) {
        if (!node.has(field)) return null;
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v;
    }
}
