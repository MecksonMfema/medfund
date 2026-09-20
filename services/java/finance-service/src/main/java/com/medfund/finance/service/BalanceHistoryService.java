package com.medfund.finance.service;

import com.medfund.finance.dto.BalanceHistoryResponse;
import com.medfund.finance.repository.BalanceHistoryQueryRepository;
import com.medfund.finance.repository.MemberBalanceSnapshotRepository;
import com.medfund.finance.repository.ProviderBalanceSnapshotRepository;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6 balance-history report (freeze-frame per executed payment run).
 * Reads the snapshot tables joined to {@code payment_runs} for run context;
 * {@code asAtRun} pins to exactly one run (D6-4), omitted means full
 * history newest-first. Rows stay native per-currency (G34).
 *
 * <p>{@code perCurrency} carries the latest frozen outstanding per
 * currency (D6-5) so the client can render a "current balance" header
 * strip independently of any filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceHistoryService {

    private final BalanceHistoryQueryRepository queryRepository;
    private final ProviderBalanceSnapshotRepository providerSnapshotRepository;
    private final MemberBalanceSnapshotRepository memberSnapshotRepository;
    private final DatabaseClient db;

    public Mono<BalanceHistoryResponse> providerHistory(UUID providerId, UUID asAtRun, String currency) {
        return Mono.zip(
                        loadProviderName(providerId),
                        queryRepository.providerHistory(providerId, asAtRun, currency).collectList())
                .map(t -> new BalanceHistoryResponse(providerId, t.getT1(), t.getT2()));
    }

    public Mono<BalanceHistoryResponse> memberHistory(UUID memberId, UUID asAtRun, String currency) {
        return Mono.zip(
                        loadMemberName(memberId),
                        queryRepository.memberHistory(memberId, asAtRun, currency).collectList())
                .map(t -> new BalanceHistoryResponse(memberId, t.getT1(), t.getT2()));
    }

    /** Latest frozen outstanding balance per currency — full history, not the filtered slice. */
    public Mono<Map<String, PerCurrencyTotal>> providerPerCurrency(UUID providerId) {
        return providerSnapshotRepository.findByProviderId(providerId)
                .collect(() -> new HashMap<String, com.medfund.finance.entity.ProviderBalanceSnapshot>(),
                        (map, s) -> map.putIfAbsent(s.getCurrencyCode(), s))
                .map(map -> {
                    Map<String, PerCurrencyTotal> out = new LinkedHashMap<>();
                    map.forEach((ccy, s) -> out.put(ccy,
                            new PerCurrencyTotal(orZero(s.getClosingBalance()), 1)));
                    return out;
                })
                .defaultIfEmpty(Map.of());
    }

    public Mono<Map<String, PerCurrencyTotal>> memberPerCurrency(UUID memberId) {
        return memberSnapshotRepository.findByMemberId(memberId)
                .collect(() -> new HashMap<String, com.medfund.finance.entity.MemberBalanceSnapshot>(),
                        (map, s) -> map.putIfAbsent(s.getCurrencyCode(), s))
                .map(map -> {
                    Map<String, PerCurrencyTotal> out = new LinkedHashMap<>();
                    map.forEach((ccy, s) -> out.put(ccy,
                            new PerCurrencyTotal(orZero(s.getClosingBalance()), 1)));
                    return out;
                })
                .defaultIfEmpty(Map.of());
    }

    /**
     * Providers are platform-scoped, so the name comes out of
     * {@code public.providers} gated on a {@code public.provider_tenants}
     * membership row: a provider this tenant has no contract with reads back
     * as a blank name rather than leaking another tenant's network.
     */
    private Mono<String> loadProviderName(UUID providerId) {
        return Mono.deferContextual(ctx -> db.sql("""
                        SELECT p.name FROM public.providers p
                         WHERE p.id = :id
                           AND EXISTS (SELECT 1 FROM public.provider_tenants pt
                                        WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId)
                        """)
                .bind("id", providerId)
                .bind("tenantId", TenantContext.requireUuid(ctx))
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .defaultIfEmpty(""));
    }

    private Mono<String> loadMemberName(UUID memberId) {
        return db.sql("SELECT first_name || ' ' || last_name AS name FROM members WHERE id = :id")
                .bind("id", memberId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .defaultIfEmpty("");
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
