package com.medfund.tenancy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.currency.Money;
import com.medfund.tenancy.entity.ExchangeRate;
import com.medfund.tenancy.exception.ExchangeRateNotFoundException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock private ExchangeRateRepository rateRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private ReactiveStringRedisTemplate redis;
    @Mock private ReactiveValueOperations<String, String> valueOps;
    @Mock private ObjectMapper objectMapper;
    @Mock private AuditPublisher auditPublisher;
    @Mock private CurrencyEventPublisher eventPublisher;

    private ExchangeRateService service;

    @BeforeEach
    void setup() {
        service = new ExchangeRateService(
                rateRepository, currencyRepository, r2dbcTemplate, redis, objectMapper,
                auditPublisher, eventPublisher);
    }

    @Test
    void recordRate_negativeAmount_rejected() {
        StepVerifier.create(service.recordRate(
                        "USD", "ZAR", new BigDecimal("-1.0"), LocalDate.now(),
                        "manual", null, "actor", "actor@test"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void recordRate_sameBaseAndQuote_rejected() {
        StepVerifier.create(service.recordRate(
                        "USD", "USD", new BigDecimal("1.0"), LocalDate.now(),
                        "manual", null, "actor", "actor@test"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void recordRate_unknownCurrency_rejected() {
        when(currencyRepository.existsActiveByCode("USD")).thenReturn(Mono.just(true));
        when(currencyRepository.existsActiveByCode("XYZ")).thenReturn(Mono.just(false));

        StepVerifier.create(service.recordRate(
                        "USD", "XYZ", new BigDecimal("1.0"), LocalDate.now(),
                        "manual", null, "actor", "actor@test"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void convert_sameCurrency_returnsSourceUnchanged() {
        Money source = Money.of(new BigDecimal("100.00"), "USD");
        StepVerifier.create(service.convert(source, "USD", LocalDate.now()))
                .assertNext(result -> assertThat(result).isEqualTo(source))
                .verifyComplete();
    }

    @Test
    void convert_missingRate_throws() {
        when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(rateRepository.findLatest(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.empty());

        Money source = Money.of(new BigDecimal("100.00"), "USD");

        StepVerifier.create(service.convert(source, "ZAR", LocalDate.now()))
                .expectError(ExchangeRateNotFoundException.class)
                .verify();
    }

    @Test
    void convert_validRate_multiplies() {
        ExchangeRate rate = new ExchangeRate();
        rate.setId(UUID.randomUUID());
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("ZAR");
        rate.setRate(new BigDecimal("18.50"));
        rate.setRateDate(LocalDate.now());
        rate.setSource("manual");

        when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(valueOps.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(rateRepository.findLatest(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(rate));

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) { }

        Money source = Money.of(new BigDecimal("10.00"), "USD");

        StepVerifier.create(service.convert(source, "ZAR", LocalDate.now()))
                .assertNext(result -> {
                    assertThat(result.currencyCode()).isEqualTo("ZAR");
                    assertThat(result.amount()).isEqualByComparingTo("185.00");
                })
                .verifyComplete();
    }
}
