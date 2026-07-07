package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.BillingService;
import com.medfund.contributions.service.LateAdjustmentService;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code medfund.users.member-changed} and, for
 * {@code changeKind=GROUP_CHANGE} events flagged {@code backdated=true},
 * moves the billed liability from the old group's ledger to the new
 * group's ledger for every already-billed month at or after the
 * effective date.
 *
 * <p>Two transactions per event:
 * <ul>
 *   <li>{@code GROUP_CHANGE_REBATE} (-) on the OLD group — reduces
 *       what the old group owes.</li>
 *   <li>{@code GROUP_CHANGE_ARREARS} (+) on the NEW group — increases
 *       what the new group now owes.</li>
 * </ul>
 * Same total amount, opposite signs — this is an accounting transfer,
 * not a new charge to the member. Member's individual balance is
 * unchanged.
 *
 * <p>No-op paths:
 * <ul>
 *   <li>{@code backdated=false} — forward-dated changes don't need
 *       ledger adjustment; the next regular billing cycle will
 *       route to the new group automatically.</li>
 *   <li>Either from_group or to_group is null (INDIVIDUAL_ONLY tenant
 *       or ungrouped member) — there's no group ledger to move
 *       between.</li>
 *   <li>Zero already-billed months at/after effective date — nothing
 *       to reallocate.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupChangedConsumer {

    private static final String TOPIC = "medfund.users.member-changed";
    private static final int MAX_MONTHS_TO_WALK = 12;

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final ContributionRepository contributionRepository;
    private final SchemeRepository schemeRepository;
    private final BillingService billingService;
    private final LateAdjustmentService lateAdjustmentService;
    private final DatabaseClient db;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.warn("GroupChanged consumer failed for record, acking anyway: {}",
                                    e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("GroupChanged consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String changeKind = optText(node, "changeKind");
            if (!"GROUP_CHANGE".equals(changeKind)) {
                // MEMBER_CHANGED envelope is shared across multiple change
                // kinds (GROUP_CHANGE, SWAP_APPLIED, …). Silently ignore
                // the ones this consumer doesn't own.
                return Mono.empty();
            }
            boolean backdated = "true".equalsIgnoreCase(optText(node, "backdated"));
            if (!backdated) {
                log.debug("Group change is forward-dated — no ledger adjustment needed");
                return Mono.empty();
            }
            String memberIdStr = node.get("memberId").asText();
            String fromStr = optText(node, "oldValue");
            String toStr   = optText(node, "newValue");
            String effDate = optText(node, "effectiveDate");
            String tenantId = optText(node, "tenantId");
            if (fromStr == null || toStr == null || effDate == null) {
                log.debug("GroupChanged event missing old/new/effectiveDate — skipping");
                return Mono.empty();
            }
            UUID memberId  = UUID.fromString(memberIdStr);
            UUID fromGroup = UUID.fromString(fromStr);
            UUID toGroup   = UUID.fromString(toStr);
            LocalDate effective = LocalDate.parse(effDate);
            Mono<Void> work = maybePostGroupTransfer(memberId, fromGroup, toGroup, effective);
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse GroupChanged event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> maybePostGroupTransfer(UUID memberId, UUID fromGroup, UUID toGroup,
                                                LocalDate effective) {
        return lookupMemberSchemeId(memberId).flatMap(schemeIdOpt -> {
            if (schemeIdOpt.isEmpty()) {
                log.debug("Member {} has no scheme — no group transfer possible", memberId);
                return Mono.<Void>empty();
            }
            UUID schemeId = schemeIdOpt.get();
            return schemeRepository.findById(schemeId)
                    .flatMap(scheme -> countBilledMonthsFrom(effective, scheme)
                            .flatMap(months -> months <= 0
                                    ? Mono.<Void>empty()
                                    : postTransfer(memberId, fromGroup, toGroup, schemeId,
                                            effective, months, currencyOf(scheme))));
        }).then();
    }

    private Mono<Void> postTransfer(UUID memberId, UUID fromGroup, UUID toGroup, UUID schemeId,
                                     LocalDate effective, int months, String currency) {
        LocalDate periodStart = effective.withDayOfMonth(1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        // Price the member on their current scheme for the effective
        // month. Same-scheme group change → monthly amount is the
        // same as the current premium.
        return billingService.priceOneMember(memberId, schemeId, periodStart, periodEnd)
                .flatMap(monthly -> {
                    if (monthly == null || monthly.signum() <= 0) {
                        log.debug("Priced monthly amount is zero — nothing to transfer");
                        return Mono.<Void>empty();
                    }
                    BigDecimal aggregate = monthly.multiply(BigDecimal.valueOf(months));
                    // Distinct sourceKey per direction so the idempotency
                    // guards in postFixedAggregate treat them as two
                    // separate entries.
                    String rebateKey  = "auto:group-change-out:" + memberId + ":" + fromGroup + "-" + toGroup;
                    String arrearsKey = "auto:group-change-in:"  + memberId + ":" + fromGroup + "-" + toGroup;
                    return lateAdjustmentService.postFixedAggregate(
                                memberId, fromGroup, schemeId, periodStart, months, aggregate,
                                currency, "GROUP_CHANGE_REBATE", rebateKey)
                            .then(lateAdjustmentService.postFixedAggregate(
                                memberId, toGroup, schemeId, periodStart, months, aggregate,
                                currency, "GROUP_CHANGE_ARREARS", arrearsKey));
                });
    }

    /**
     * Walk forward from the effective date counting already-billed
     * months. Same routine as {@code SchemeChangedConsumer}.
     */
    private Mono<Integer> countBilledMonthsFrom(LocalDate effective, Scheme scheme) {
        String line = scheme.getInsuranceLine() != null ? scheme.getInsuranceLine() : "HEALTH";
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate start = effective.withDayOfMonth(1);
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

    private Mono<Optional<UUID>> lookupMemberSchemeId(UUID memberId) {
        return db.sql("SELECT scheme_id FROM members WHERE id = :id")
                .bind("id", memberId)
                .map(row -> Optional.ofNullable(row.get("scheme_id", UUID.class)))
                .one()
                .defaultIfEmpty(Optional.empty())
                .onErrorReturn(Optional.empty());
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
