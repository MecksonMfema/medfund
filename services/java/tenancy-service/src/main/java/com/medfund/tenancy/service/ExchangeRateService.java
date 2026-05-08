package com.medfund.tenancy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.currency.CurrencyConverter;
import com.medfund.shared.currency.Money;
import com.medfund.tenancy.entity.ExchangeRate;
import com.medfund.tenancy.exception.ExchangeRateNotFoundException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService implements CurrencyConverter {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final BigDecimal SWING_THRESHOLD = new BigDecimal("0.10");

    private final ExchangeRateRepository rateRepository;
    private final CurrencyRepository currencyRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditPublisher auditPublisher;
    private final CurrencyEventPublisher currencyEventPublisher;

    // ── Lookup ────────────────────────────────────────────────────────────────

    public Mono<ExchangeRate> findLatest(String base, String quote, LocalDate asOf, UUID tenantId) {
        if (base.equals(quote)) {
            return Mono.error(new IllegalArgumentException("base and quote must differ"));
        }
        String key = cacheKey(base, quote, asOf, tenantId);
        return redis.opsForValue().get(key)
                .flatMap(this::deserialize)
                .switchIfEmpty(rateRepository.findLatest(base, quote, asOf, tenantId)
                        .flatMap(rate -> serializeAndCache(key, rate).thenReturn(rate)));
    }

    public Flux<ExchangeRate> history(String base, String quote, LocalDate from, LocalDate to, UUID tenantId) {
        return rateRepository.findHistory(base, quote, from, to, tenantId);
    }

    // ── CurrencyConverter contract ───────────────────────────────────────────

    @Override
    public Mono<Money> convert(Money source, String targetCurrency, LocalDate asOf) {
        if (source.currencyCode().equals(targetCurrency)) {
            return Mono.just(source);
        }
        return findLatest(source.currencyCode(), targetCurrency, asOf, null)
                .switchIfEmpty(Mono.error(new ExchangeRateNotFoundException(
                        source.currencyCode(), targetCurrency, asOf)))
                .map(rate -> {
                    BigDecimal converted = source.amount().multiply(rate.getRate());
                    return Money.of(converted, targetCurrency);
                });
    }

    // ── Recording ────────────────────────────────────────────────────────────

    public Mono<ExchangeRate> recordRate(String base, String quote, BigDecimal rate, LocalDate rateDate,
                                         String source, UUID tenantId, String actorId, String actorEmail) {
        if (base.equals(quote)) {
            return Mono.error(new IllegalArgumentException("base and quote must differ"));
        }
        if (rate.signum() <= 0) {
            return Mono.error(new IllegalArgumentException("rate must be positive"));
        }

        return Mono.zip(
                        currencyRepository.existsActiveByCode(base),
                        currencyRepository.existsActiveByCode(quote))
                .flatMap(t -> {
                    if (Boolean.FALSE.equals(t.getT1()) || Boolean.FALSE.equals(t.getT2())) {
                        return Mono.<ExchangeRate>error(new IllegalArgumentException(
                                "currency_code must reference an active row in public.currencies"));
                    }
                    return rateRepository.findLatest(base, quote, rateDate, tenantId)
                            .flatMap(prev -> warnOnLargeSwing(prev.getRate(), rate, base, quote))
                            .then(insertAndPublish(base, quote, rate, rateDate, source, tenantId, actorId, actorEmail));
                });
    }

    private Mono<ExchangeRate> insertAndPublish(String base, String quote, BigDecimal rate, LocalDate rateDate,
                                                String source, UUID tenantId, String actorId, String actorEmail) {
        ExchangeRate row = new ExchangeRate();
        row.setBaseCurrency(base);
        row.setQuoteCurrency(quote);
        row.setRate(rate);
        row.setRateDate(rateDate);
        row.setSource(source != null ? source : "manual");
        row.setTenantId(tenantId);
        row.setCreatedBy(actorId != null ? UUID.fromString(actorId) : null);

        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAuditCreate(saved, actorId, actorEmail).thenReturn(saved))
                .flatMap(saved -> currencyEventPublisher.publishRateUpdated(saved).thenReturn(saved))
                .flatMap(saved -> evictCache(base, quote, tenantId).thenReturn(saved));
    }

    private Mono<Void> warnOnLargeSwing(BigDecimal previous, BigDecimal next, String base, String quote) {
        if (previous == null || previous.signum() <= 0) {
            return Mono.empty();
        }
        BigDecimal delta = next.subtract(previous).abs()
                .divide(previous, 10, RoundingMode.HALF_EVEN);
        if (delta.compareTo(SWING_THRESHOLD) > 0) {
            log.warn("Exchange-rate swing >10% detected for {}/{}: {} -> {}", base, quote, previous, next);
        }
        return Mono.empty();
    }

    // ── Cache helpers ────────────────────────────────────────────────────────

    private String cacheKey(String base, String quote, LocalDate asOf, UUID tenantId) {
        return String.format("fx:%s:%s:%s:%s", base, quote, asOf,
                tenantId != null ? tenantId : "_");
    }

    private Mono<ExchangeRate> deserialize(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, ExchangeRate.class));
        } catch (JsonProcessingException e) {
            return Mono.empty();
        }
    }

    private Mono<Void> serializeAndCache(String key, ExchangeRate rate) {
        try {
            String json = objectMapper.writeValueAsString(rate);
            return redis.opsForValue().set(key, json, CACHE_TTL).then();
        } catch (JsonProcessingException e) {
            return Mono.empty();
        }
    }

    private Mono<Void> evictCache(String base, String quote, UUID tenantId) {
        String pattern = String.format("fx:%s:%s:*:%s", base, quote,
                tenantId != null ? tenantId : "_");
        return redis.keys(pattern).collectList()
                .flatMap(keys -> keys.isEmpty() ? Mono.just(0L) : redis.delete(keys.toArray(String[]::new)))
                .then();
    }

    // ── Audit ────────────────────────────────────────────────────────────────

    private Mono<Void> publishAuditCreate(ExchangeRate rate, String actorId, String actorEmail) {
        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("baseCurrency", rate.getBaseCurrency());
        newMap.put("quoteCurrency", rate.getQuoteCurrency());
        newMap.put("rate", rate.getRate().toPlainString());
        newMap.put("rateDate", rate.getRateDate().toString());
        newMap.put("source", rate.getSource());
        newMap.put("tenantId", rate.getTenantId() != null ? rate.getTenantId().toString() : null);

        var event = AuditEvent.create(
                "platform",
                "EXCHANGE_RATE",
                rate.getId().toString(),
                rate.getBaseCurrency() + "->" + rate.getQuoteCurrency(),
                "CREATE",
                actorId,
                actorEmail,
                null,
                newMap,
                null,
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
