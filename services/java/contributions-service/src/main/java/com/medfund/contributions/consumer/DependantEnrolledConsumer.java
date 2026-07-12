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
 * Consumes {@code medfund.users.dependant-enrolled} and auto-posts a
 * {@code LATE_ENROLMENT_CHARGE} when a new dependant's effective date
 * falls in a billing period already committed for their parent
 * member's scheme (V047).
 *
 * <p>Mirrors {@link MemberEnrolledConsumer} exactly — same walk logic,
 * same idempotency shape, same fire-and-forget error posture — but
 * routes through {@link LateAdjustmentService#postDependantAggregate}
 * so the charge is priced from the DEPENDANT's own age band (child
 * rate) rather than the parent's (adult rate).
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
public class DependantEnrolledConsumer {

    private static final String TOPIC = "medfund.users.dependant-enrolled";
    // Same cap as MemberEnrolledConsumer — a household back-dating a
    // dependant 12+ months is exceptional; a longer walk should be a
    // manual reconciliation.
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
                            log.warn("DependantEnrolled consumer failed for record, acking anyway: {}",
                                    e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("DependantEnrolled consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String dependantIdStr = node.get("dependantId").asText();
            String memberIdStr    = node.get("memberId").asText();
            String groupIdStr     = optText(node, "groupId");
            String schemeIdStr    = optText(node, "schemeId");
            String enrollDate     = optText(node, "enrollmentDate");
            String tenantId       = optText(node, "tenantId");
            if (schemeIdStr == null || enrollDate == null) {
                log.debug("DependantEnrolled event missing schemeId/enrollmentDate — skipping late-adjustment check");
                return Mono.empty();
            }
            UUID dependantId = UUID.fromString(dependantIdStr);
            UUID memberId    = UUID.fromString(memberIdStr);
            UUID groupId     = groupIdStr != null ? UUID.fromString(groupIdStr) : null;
            UUID schemeId    = UUID.fromString(schemeIdStr);
            LocalDate enrollment = LocalDate.parse(enrollDate);
            String dobStr = optText(node, "dateOfBirth");
            LocalDate dateOfBirth = dobStr != null ? LocalDate.parse(dobStr) : null;
            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
            // V061: seed the beneficiary_benefits ledger for the new dependant.
            // Runs regardless of enrolment being past/current — the seeder
            // is only concerned with schemeId + enrollmentDate.
            Mono<Void> seed = beneficiaryBenefitSeeder.seed(memberId, dependantId, schemeId,
                                                             enrollment, dateOfBirth);
            // Skip only future enrolments. Current-month falls through
            // so countArrearsMonths can probe for an already-billed
            // current cycle (V048).
            Mono<Void> lateCharge = enrollment.isAfter(currentMonth)
                    ? Mono.empty()
                    : maybePostLateEnrolment(dependantId, memberId, groupId, schemeId, enrollment);
            Mono<Void> work = seed.then(lateCharge);
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse DependantEnrolled event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> maybePostLateEnrolment(UUID dependantId, UUID memberId, UUID groupId,
                                                UUID schemeId, LocalDate enrollment) {
        return schemeRepository.findById(schemeId)
                .flatMap(scheme -> countArrearsMonths(enrollment, scheme)
                        .flatMap(months -> months <= 0
                                ? Mono.<Void>empty()
                                : lateAdjustmentService.postDependantAggregate(
                                        dependantId, memberId, groupId, schemeId,
                                        enrollment, months,
                                        currencyOf(scheme),
                                        "LATE_ENROLMENT_CHARGE",
                                        // Source key partitions dependant events by
                                        // dependant id so a late-enrol event for two
                                        // dependants of the same member don't collide
                                        // on idempotency.
                                        dependantId.toString())))
                .then();
    }

    /**
     * Count arrears months. Two components — see
     * {@link MemberEnrolledConsumer#countArrearsMonths} for the full
     * rationale. Same shape: past complete months (unconditional) plus
     * the current month IFF the tenant already ran billing for it
     * before this dependant existed.
     */
    private Mono<Integer> countArrearsMonths(LocalDate enrollment, Scheme scheme) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate enrollmentMonth = enrollment.withDayOfMonth(1);
        long pastMonths = Math.max(0, Math.min(
                java.time.temporal.ChronoUnit.MONTHS.between(enrollmentMonth, currentMonth),
                MAX_MONTHS_TO_WALK));

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
