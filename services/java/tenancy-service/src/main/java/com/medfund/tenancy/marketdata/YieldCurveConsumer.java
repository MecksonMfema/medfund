package com.medfund.tenancy.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.tenancy.service.TenantYieldCurveService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

/**
 * Consumes {@code medfund.market-data.yield-curve-updated} events published
 * by the Phase 24 Go {@code market-data-service} and upserts each tenor
 * point into {@code public.tenant_yield_curve} (Phase 15 §24 / I12 + I16).
 *
 * <p>Event schema (v1):
 * <pre>
 * {
 *   "schema_version": 1,
 *   "tenant_id": "uuid",
 *   "currency": "USD",
 *   "source":   "RBZ_AUTO" | "SARB_AUTO",
 *   "effective_from": "2026-08-30",       // ISO date
 *   "points": [
 *     {"tenor_months": 12, "spot_rate": "0.0500000"},
 *     {"tenor_months": 24, "spot_rate": "0.0550000"}
 *   ]
 * }
 * </pre>
 *
 * <p>Ack contract: {@code .doOnSuccess} on the per-record processing
 * pipeline (per {@code bug_reactor_kafka_ack_swallow}). Malformed payloads
 * ack after logging so a poison message doesn't stall the partition —
 * matches the {@code PolicyIssuedConsumer} pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YieldCurveConsumer {

    static final String TOPIC = "medfund.market-data.yield-curve-updated";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReceiverOptions<String, String> receiverOptions;
    private final TenantYieldCurveService yieldCurveService;

    @PostConstruct
    public void consume() {
        KafkaReceiver.create(receiverOptions.subscription(Collections.singleton(TOPIC)))
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(err -> {
                            log.error("yield-curve-updated consumer failed — full chain: {}. "
                                            + "Ack anyway to unblock partition.",
                                    chainMessages(err), err);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("yield-curve-updated consumer stream error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    Mono<Void> processRecord(String json) {
        return Mono.defer(() -> {
            JsonNode root;
            try {
                root = MAPPER.readTree(json);
            } catch (Exception e) {
                log.error("yield-curve-updated payload unparseable — full chain: {}", chainMessages(e), e);
                return Mono.empty();
            }
            UUID tenantId;
            String currency;
            String source;
            LocalDate effectiveFrom;
            try {
                tenantId = UUID.fromString(root.path("tenant_id").asText());
                currency = root.path("currency").asText();
                source = root.path("source").asText();
                String effRaw = root.path("effective_from").asText(null);
                effectiveFrom = (effRaw == null || effRaw.isBlank()) ? LocalDate.now() : LocalDate.parse(effRaw);
            } catch (Exception e) {
                log.error("yield-curve-updated header missing tenant_id / currency / source — dropping: {}",
                        chainMessages(e));
                return Mono.empty();
            }
            if (currency.isBlank() || source.isBlank()) {
                log.error("yield-curve-updated header blank currency={} source={} — dropping", currency, source);
                return Mono.empty();
            }
            JsonNode points = root.path("points");
            if (!points.isArray() || points.isEmpty()) {
                log.warn("yield-curve-updated tenant={} currency={}: no points in payload, dropping",
                        tenantId, currency);
                return Mono.empty();
            }
            return Flux.fromIterable(points)
                    .concatMap(p -> upsertPoint(tenantId, currency, source, effectiveFrom, p))
                    .then();
        });
    }

    private Mono<?> upsertPoint(UUID tenantId, String currency, String source,
                                 LocalDate effectiveFrom, JsonNode point) {
        int tenorMonths;
        BigDecimal spotRate;
        try {
            tenorMonths = point.path("tenor_months").asInt();
            spotRate = new BigDecimal(point.path("spot_rate").asText());
        } catch (Exception e) {
            log.warn("yield-curve-updated tenant={} currency={}: skipping malformed point {} — {}",
                    tenantId, currency, point, chainMessages(e));
            return Mono.empty();
        }
        if (tenorMonths < 1 || tenorMonths > 600) {
            log.warn("yield-curve-updated tenant={} currency={}: tenor_months out of range: {}",
                    tenantId, currency, tenorMonths);
            return Mono.empty();
        }
        return yieldCurveService.upsertAutoFetched(
                tenantId, currency, tenorMonths, spotRate, source, effectiveFrom);
    }

    static String chainMessages(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        while (c != null) {
            sb.append(" ← ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }
}
