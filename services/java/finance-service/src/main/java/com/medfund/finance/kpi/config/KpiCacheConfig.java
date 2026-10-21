package com.medfund.finance.kpi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.kpi.dto.KpiReportData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * K10 — 15-minute Redis TTL for composed KPI results. Serialises
 * {@link KpiReportData} via Jackson to survive a JVM restart. The template
 * is namespaced (KPI-only) to keep the serializer typed and to avoid
 * touching other Redis-using code in finance-service.
 *
 * <p>Reactive connection factory is provided by Spring Boot auto-configuration
 * from {@code spring.data.redis.*} in {@code application.yml}.
 */
@Configuration
public class KpiCacheConfig {

    @Bean
    public ReactiveRedisTemplate<String, KpiReportData> kpiReportDataRedisTemplate(
            ReactiveRedisConnectionFactory factory, ObjectMapper mapper) {
        Jackson2JsonRedisSerializer<KpiReportData> valueSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, KpiReportData.class);
        RedisSerializationContext<String, KpiReportData> context = RedisSerializationContext
                .<String, KpiReportData>newSerializationContext(new StringRedisSerializer())
                .value(valueSerializer)
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }
}
