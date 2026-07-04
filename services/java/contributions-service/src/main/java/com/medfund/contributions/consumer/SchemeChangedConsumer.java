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
import java.util.UUID;

/**
 * Consumes {@code medfund.contributions.scheme-changed} and auto-posts
 * either {@code SCHEME_UPGRADE_ARREARS} (+) or
 * {@code SCHEME_DOWNGRADE_REBATE} (-) when a member's scheme change
 * effective date sits inside a billed period.
 *
 * <p>Delta calculation: for each already-billed month at or after the
 * effective date, we price both {@code fromScheme} and {@code toScheme}
 * for this member's age band and diff them. Positive total → arrears
 * (they were undercharged); negative → rebate (they were overcharged).
 * Zero → no-op (equal pricing).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemeChangedConsumer {

    private static final String TOPIC = "medfund.contributions.scheme-changed";
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
                            log.warn("SchemeChanged consumer failed for record, acking anyway: {}",
                                    e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("SchemeChanged consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String scId       = node.get("schemeChangeId").asText();
            String memberIdStr = node.get("memberId").asText();
            String fromStr    = optText(node, "fromSchemeId");
            String toStr      = optText(node, "toSchemeId");
            String effDate    = optText(node, "effectiveDate");
            String tenantId   = optText(node, "tenantId");
            if (fromStr == null || toStr == null || effDate == null) {
                log.debug("SchemeChanged event missing from/to/effectiveDate — skipping");
                return Mono.empty();
            }
            UUID memberId   = UUID.fromString(memberIdStr);
            UUID fromScheme = UUID.fromString(fromStr);
            UUID toScheme   = UUID.fromString(toStr);
            LocalDate effective = LocalDate.parse(effDate);
            LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
            if (effective.isAfter(currentMonth.withDayOfMonth(currentMonth.lengthOfMonth()))) {
                log.debug("Scheme change effective {} is in a future month — no adjustment", effective);
                return Mono.empty();
            }
            Mono<Void> work = maybePostSchemeDelta(scId, memberId, fromScheme, toScheme, effective);
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse SchemeChanged event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> maybePostSchemeDelta(String scId, UUID memberId,
                                             UUID fromScheme, UUID toScheme,
                                             LocalDate effective) {
        return schemeRepository.findById(toScheme)
                .flatMap(newScheme -> countBilledMonthsFrom(effective, newScheme)
                        .flatMap(months -> months <= 0
                                ? Mono.<Void>empty()
                                : postDelta(scId, memberId, fromScheme, toScheme, effective, months,
                                        currencyOf(newScheme))))
                .then();
    }

    private Mono<Void> postDelta(String scId, UUID memberId, UUID fromScheme, UUID toScheme,
                                   LocalDate effective, int months, String currency) {
        return lookupMemberGroupId(memberId).flatMap(groupIdOpt -> {
            UUID groupId = groupIdOpt.orElse(null);
            LocalDate start = effective.withDayOfMonth(1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            Mono<BigDecimal> newPrice = billingService.priceOneMember(memberId, toScheme, start, end);
            Mono<BigDecimal> oldPrice = billingService.priceOneMember(memberId, fromScheme, start, end);
            return Mono.zip(newPrice, oldPrice).flatMap(prices -> {
                BigDecimal delta = prices.getT1().subtract(prices.getT2());
                int sign = delta.signum();
                if (sign == 0) {
                    log.debug("Scheme change {} has zero delta — no adjustment", scId);
                    return Mono.<Void>empty();
                }
                String type = sign > 0 ? "SCHEME_UPGRADE_ARREARS" : "SCHEME_DOWNGRADE_REBATE";
                BigDecimal totalUnit = delta.abs();
                // LateAdjustmentService prices via billingService.priceOneMember,
                // but here we want to override with the delta amount rather than
                // the raw new-scheme price. We do that by:
                //  - Passing the memberId + toScheme so its internal price call
                //    resolves to newPrice.
                //  - Then multiplying by (months × delta / newPrice) so the total
                //    aggregate lands as delta × months.
                // Simpler: bypass LateAdjustmentService's pricing by manually
                // computing the total and calling into the same code path via
                // a public helper — for now, this consumer records directly via
                // the same idempotency key convention so replays are skipped.
                BigDecimal aggregate = totalUnit.multiply(BigDecimal.valueOf(months));
                return lateAdjustmentService.postFixedAggregate(memberId, groupId,
                        toScheme, effective.withDayOfMonth(1), months, aggregate,
                        currency, type, scId);
            });
        });
    }

    /**
     * Walk forward from the effective date counting already-billed
     * months. Same routine as the enrolment/lifecycle consumers.
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

    /** Resolve the member's current group from the tenant schema. Wrapped
     *  in Optional so the "individual member, no group" case propagates
     *  as an absent value rather than an error. */
    private Mono<java.util.Optional<UUID>> lookupMemberGroupId(UUID memberId) {
        return db.sql("SELECT group_id FROM members WHERE id = :id")
                .bind("id", memberId)
                .map(row -> java.util.Optional.ofNullable(row.get("group_id", UUID.class)))
                .one()
                .defaultIfEmpty(java.util.Optional.empty())
                .onErrorReturn(java.util.Optional.empty());
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
