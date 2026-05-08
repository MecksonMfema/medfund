package com.medfund.tenancy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.tenancy.entity.ExchangeRate;
import com.medfund.tenancy.entity.TenantCurrencyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyEventPublisher {

    private static final String TENANT_CURRENCY_TOPIC = "medfund.tenant.currency-updated";
    private static final String RATE_TOPIC            = "medfund.currency.rate-updated";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publishTenantCurrencyUpdated(TenantCurrencyConfig config, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "TENANT_CURRENCY_" + action);
        payload.put("tenantId", config.getTenantId().toString());
        payload.put("currencyCode", config.getCurrencyCode());
        payload.put("isDefault", config.getIsDefault());
        payload.put("isActive", config.getIsActive());
        return publishEvent(TENANT_CURRENCY_TOPIC, config.getTenantId().toString(), payload);
    }

    public Mono<Void> publishRateUpdated(ExchangeRate rate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "EXCHANGE_RATE_RECORDED");
        payload.put("rateId", rate.getId().toString());
        payload.put("baseCurrency", rate.getBaseCurrency());
        payload.put("quoteCurrency", rate.getQuoteCurrency());
        payload.put("rate", rate.getRate().toPlainString());
        payload.put("rateDate", rate.getRateDate().toString());
        payload.put("source", rate.getSource());
        payload.put("tenantId", rate.getTenantId() != null ? rate.getTenantId().toString() : null);
        String key = rate.getBaseCurrency() + ":" + rate.getQuoteCurrency();
        return publishEvent(RATE_TOPIC, key, payload);
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, Object> payload) {
        try {
            String value = objectMapper.writeValueAsString(payload);
            var record = new ProducerRecord<>(topic, key, value);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.error("Failed to publish event to {}: {}", topic, e.getMessage()))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
