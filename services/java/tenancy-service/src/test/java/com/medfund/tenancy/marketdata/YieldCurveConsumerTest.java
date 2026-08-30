package com.medfund.tenancy.marketdata;

import com.medfund.tenancy.entity.TenantYieldCurve;
import com.medfund.tenancy.service.TenantYieldCurveService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-style test for the yield-curve Kafka consumer. Focuses on the
 * payload parsing + per-point dispatch — the Kafka wiring is left to
 * an integration-flavour test if follow-up needs it.
 */
class YieldCurveConsumerTest {

    private final TenantYieldCurveService yieldCurveService = mock(TenantYieldCurveService.class);
    private final ReceiverOptions<String, String> receiverOptions = mock(ReceiverOptions.class);
    private final YieldCurveConsumer consumer = new YieldCurveConsumer(receiverOptions, yieldCurveService);

    private final UUID tenantId = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void processRecord_upsertsEveryValidPoint() {
        List<Integer> tenors = new ArrayList<>();
        when(yieldCurveService.upsertAutoFetched(any(), anyString(), anyInt(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    tenors.add(invocation.getArgument(2, Integer.class));
                    TenantYieldCurve row = new TenantYieldCurve();
                    row.setId(UUID.randomUUID());
                    return Mono.just(row);
                });

        String json = "{"
                + "\"schema_version\":1,"
                + "\"tenant_id\":\"" + tenantId + "\","
                + "\"currency\":\"USD\","
                + "\"source\":\"RBZ_AUTO\","
                + "\"effective_from\":\"2026-08-30\","
                + "\"points\":["
                + "  {\"tenor_months\":12,\"spot_rate\":\"0.0500000\"},"
                + "  {\"tenor_months\":60,\"spot_rate\":\"0.0575000\"}"
                + "]}";

        consumer.processRecord(json).block();

        verify(yieldCurveService, times(2)).upsertAutoFetched(
                any(), anyString(), anyInt(), any(), anyString(), any());
        assertEquals(List.of(12, 60), tenors);
    }

    @Test
    void processRecord_skipsPointsOutOfTenorRange() {
        when(yieldCurveService.upsertAutoFetched(any(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(Mono.just(new TenantYieldCurve()));

        String json = "{"
                + "\"tenant_id\":\"" + tenantId + "\","
                + "\"currency\":\"USD\","
                + "\"source\":\"RBZ_AUTO\","
                + "\"effective_from\":\"2026-01-01\","
                + "\"points\":["
                + "  {\"tenor_months\":0,\"spot_rate\":\"0.05\"},"
                + "  {\"tenor_months\":700,\"spot_rate\":\"0.05\"},"
                + "  {\"tenor_months\":24,\"spot_rate\":\"0.055\"}"
                + "]}";

        consumer.processRecord(json).block();

        verify(yieldCurveService, times(1)).upsertAutoFetched(
                eq(tenantId), eq("USD"), eq(24), any(BigDecimal.class),
                eq("RBZ_AUTO"), eq(LocalDate.parse("2026-01-01")));
    }

    @Test
    void processRecord_dropsPayloadWithoutTenantId() {
        String json = "{\"currency\":\"USD\",\"source\":\"RBZ_AUTO\","
                + "\"points\":[{\"tenor_months\":12,\"spot_rate\":\"0.05\"}]}";

        consumer.processRecord(json).block();

        verify(yieldCurveService, never()).upsertAutoFetched(
                any(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void processRecord_dropsMalformedJson() {
        // Should not throw — the malformed payload is logged + returned as
        // Mono.empty() so the outer consumer can ack + move on.
        consumer.processRecord("not-json{{{").block();

        verify(yieldCurveService, never()).upsertAutoFetched(
                any(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void processRecord_dropsPayloadWithNoPoints() {
        String json = "{"
                + "\"tenant_id\":\"" + tenantId + "\","
                + "\"currency\":\"USD\","
                + "\"source\":\"RBZ_AUTO\","
                + "\"points\":[]}";

        consumer.processRecord(json).block();

        verify(yieldCurveService, never()).upsertAutoFetched(
                any(), anyString(), anyInt(), any(), anyString(), any());
    }
}
