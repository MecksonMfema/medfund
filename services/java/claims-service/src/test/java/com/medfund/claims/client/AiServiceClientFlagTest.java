package com.medfund.claims.client;

import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.shared.flags.FlagRegistry;
import com.medfund.shared.flags.PlatformFlag;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the platform-flag short-circuit in {@link AiServiceClient}. Uses
 * an unreachable AI-service URL so any HTTP hit would fail loudly — the
 * flag-off path must return {@link AiSignals#empty()} without ever
 * touching the network.
 */
class AiServiceClientFlagTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1"; // reserved port

    private final FraudRequestBuilder fraudRequestBuilder = new FraudRequestBuilder();

    @Test
    void evaluate_returnsEmptySignals_whenBothFlagsOff() {
        AlwaysReturnsFlag flags = new AlwaysReturnsFlag(Map.of(
                PlatformFlag.AI_ADJUDICATION, false,
                PlatformFlag.FRAUD_DETECTION, false));
        AiServiceClient client = new AiServiceClient(UNREACHABLE, fraudRequestBuilder, flags);

        StepVerifier.create(client.evaluate(sampleClaim(), sampleLines())
                .contextWrite(withTenant("t1")))
                .assertNext(signals -> assertThat(signals).isEqualTo(AiSignals.empty()))
                .verifyComplete();
        assertThat(flags.calls).containsKey(PlatformFlag.AI_ADJUDICATION);
        assertThat(flags.calls).containsKey(PlatformFlag.FRAUD_DETECTION);
    }

    @Test
    void evaluate_returnsEmptySignals_whenNoTenantContext() {
        AlwaysReturnsFlag flags = new AlwaysReturnsFlag(Map.of(
                PlatformFlag.AI_ADJUDICATION, true,
                PlatformFlag.FRAUD_DETECTION, true));
        AiServiceClient client = new AiServiceClient(UNREACHABLE, fraudRequestBuilder, flags);

        StepVerifier.create(client.evaluate(sampleClaim(), sampleLines()))
                .assertNext(signals -> assertThat(signals).isEqualTo(AiSignals.empty()))
                .verifyComplete();
        // Never asked the flag registry — short-circuited on missing tenant
        assertThat(flags.calls).isEmpty();
    }

    private static Context withTenant(String tenantId) {
        return TenantContext.put(Context.empty(), tenantId);
    }

    private Claim sampleClaim() {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setProviderId(UUID.randomUUID());
        c.setInsuranceLine("HEALTH");
        c.setClaimedAmount(new BigDecimal("500.00"));
        c.setCurrencyCode("USD");
        c.setServiceDate(LocalDate.of(2026, 9, 15));
        c.setClaimType("medical");
        c.setDiagnosisCodes("[\"K35\"]");
        return c;
    }

    private List<ClaimLine> sampleLines() {
        ClaimLine l = new ClaimLine();
        l.setTariffCode("23410");
        return List.of(l);
    }

    /** Stub FlagRegistry that records every call and returns configured values. */
    private static final class AlwaysReturnsFlag implements FlagRegistry {
        final Map<PlatformFlag, Boolean> values;
        final Map<PlatformFlag, Integer> calls = new HashMap<>();

        AlwaysReturnsFlag(Map<PlatformFlag, Boolean> values) {
            this.values = values;
        }

        @Override
        public Mono<Boolean> isEnabled(PlatformFlag flag) {
            calls.merge(flag, 1, Integer::sum);
            return Mono.just(values.getOrDefault(flag, false));
        }
    }
}
