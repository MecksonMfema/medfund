package com.medfund.finance.regulatory.naic;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * SPI for probing {@code public.us_tenant_naic_config} presence — the
 * NAIC controllers refuse with 422 when a US tenant has not been
 * onboarded (no company code / group code / FEIN / state on file). Phase
 * 14 delivers the concrete Spring bean backed by an R2DBC query; Phase 12
 * ships the interface with an {@link Optional}-injected consumer so
 * regardless of whether Phase 14 has landed:
 *
 * <ul>
 *   <li>No bean registered → controller assumes "no config" → 422 on every
 *       submit. This is the desired state pre-Phase-14: NAIC reports are
 *       gated on real onboarding data that doesn't yet exist.</li>
 *   <li>Bean registered → controller consults it per submit. Missing rows
 *       yield 422; present rows pass through and the shaper reads the
 *       identity fields from the same table.</li>
 * </ul>
 *
 * <p>The reader deliberately returns {@code Mono<Boolean>} rather than
 * loading the full config in this contract — the shaper's raw-data
 * provider (Phase 12b) will re-read once for the identity fields. Split
 * responsibilities keep the gate check cheap and independently mockable.
 */
public interface UsTenantNaicConfigReader {

    /**
     * @return {@code Mono.just(true)} when the tenant has an effective
     *         {@code us_tenant_naic_config} row on the report date;
     *         {@code Mono.just(false)} otherwise. Errors on the query
     *         path should resolve to {@code Mono.just(false)} so a
     *         database blip fails closed (better to over-reject than
     *         over-permit a regulator submission).
     */
    Mono<Boolean> hasEffectiveConfig(UUID tenantId);
}
