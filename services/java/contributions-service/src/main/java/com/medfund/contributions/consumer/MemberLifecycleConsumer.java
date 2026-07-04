package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.SchemeRepository;
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
 * Consumes {@code medfund.users.member-lifecycle} and auto-posts a
 * {@code LATE_TERMINATION_CREDIT} when a member is terminated with an
 * effective date inside a period the system has already billed for
 * their scheme.
 *
 * <p>Scenario: July was billed for the group last week; on 20 July the
 * tenant records the member's termination with
 * {@code termination_date=2026-07-01}. The July premium was invoiced
 * to the group but the member shouldn't have been counted. This
 * consumer notices the overlap and posts a rebate for one month.
 *
 * <p>No-op paths:
 * <ul>
 *   <li>Status ≠ {@code terminated} (activation, suspension, etc.).
 *   <li>Termination date missing or in the future.
 *   <li>Scheme lookup fails.
 *   <li>Zero billed periods overlap the termination window.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberLifecycleConsumer {

    private static final String TOPIC = "medfund.users.member-lifecycle";
    private static final int MAX_MONTHS_TO_WALK = 12;

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final ContributionRepository contributionRepository;
    private final SchemeRepository schemeRepository;
    private final LateAdjustmentService lateAdjustmentService;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.warn("MemberLifecycle consumer failed for record, acking anyway: {}",
                                    e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("MemberLifecycle consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String status = optText(node, "status");
            if (!"terminated".equalsIgnoreCase(status)) return Mono.empty();
            String memberIdStr = node.get("memberId").asText();
            String groupIdStr  = optText(node, "groupId");
            String schemeIdStr = optText(node, "schemeId");
            String termDate    = optText(node, "terminationDate");
            String tenantId    = optText(node, "tenantId");
            if (schemeIdStr == null || termDate == null) {
                log.debug("MemberLifecycle event missing schemeId/terminationDate — skipping");
                return Mono.empty();
            }
            UUID memberId = UUID.fromString(memberIdStr);
            UUID groupId  = groupIdStr != null ? UUID.fromString(groupIdStr) : null;
            UUID schemeId = UUID.fromString(schemeIdStr);
            LocalDate termination = LocalDate.parse(termDate);
            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
            if (termination.isAfter(currentMonth.withDayOfMonth(currentMonth.lengthOfMonth()))) {
                log.debug("Termination {} is in a future month — no rebate needed", termination);
                return Mono.empty();
            }
            Mono<Void> work = maybePostRebate(memberId, groupId, schemeId, termination);
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse MemberLifecycle event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> maybePostRebate(UUID memberId, UUID groupId,
                                        UUID schemeId, LocalDate termination) {
        return schemeRepository.findById(schemeId)
                .flatMap(scheme -> countBilledMonthsFrom(termination, scheme)
                        .flatMap(months -> months <= 0
                                ? Mono.<Void>empty()
                                : lateAdjustmentService.postAggregate(
                                        memberId, groupId, schemeId,
                                        termination.withDayOfMonth(1), months,
                                        currencyOf(scheme),
                                        "LATE_TERMINATION_CREDIT",
                                        memberId.toString())))
                .then();
    }

    /**
     * How many months from the termination date onwards have already
     * been billed? Same forward walk as
     * {@link MemberEnrolledConsumer} but the caller's semantics differ
     * — here we're counting "how many billed periods DID cover this
     * member incorrectly?" rather than "how many did we miss?". The
     * arithmetic is identical.
     */
    private Mono<Integer> countBilledMonthsFrom(LocalDate termination, Scheme scheme) {
        String line = scheme.getInsuranceLine() != null ? scheme.getInsuranceLine() : "HEALTH";
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate start = termination.withDayOfMonth(1);
        return Flux.range(0, MAX_MONTHS_TO_WALK)
                .map(i -> start.plusMonths(i))
                .takeWhile(m -> !m.isAfter(currentMonth))
                .concatMap(m -> contributionRepository
                        .countByPeriodAndLine(m, m.withDayOfMonth(m.lengthOfMonth()), line)
                        .map(c -> c != null && c > 0L))
                .takeWhile(billed -> billed)
                .count()
                .map(Long::intValue);
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
