package com.medfund.finance.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reads a member's outstanding contribution balance from the
 * {@code member_running_balance} table owned by contributions-service.
 *
 * <p>Both services share the tenant-schema Postgres, so this is a direct
 * R2DBC read rather than a cross-service HTTP call — see the Phase 4
 * deviation note in {@code thoughts/shared/plans/2026-08-09-ctc-payments.md}
 * (short version: the auto-CTC path runs inside a Kafka consumer with
 * no JWT context, and the codebase has no service-to-service auth glue,
 * so R2DBC is both simpler and consistent with how finance-service
 * already reads other tenant tables).
 *
 * <p>Query stays unqualified per {@code bug_public_prefix_silent_rollback}
 * — {@code member_running_balance} is a tenant-schema table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberContributionBalanceReader {

    private final DatabaseClient db;

    /**
     * Outstanding balance for a member in a specific currency. Returns
     * zero when the member has no row for that currency yet — matches
     * {@code BalanceService.getMemberBalance} which zero-defaults on
     * absent rows.
     */
    public Mono<BigDecimal> getBalance(UUID memberId, String currencyCode) {
        if (memberId == null || currencyCode == null) {
            return Mono.just(BigDecimal.ZERO);
        }
        return db.sql("""
                SELECT balance
                  FROM member_running_balance
                 WHERE member_id = :memberId
                   AND currency_code = :currency
                """)
                .bind("memberId", memberId)
                .bind("currency", currencyCode)
                .map((row, meta) -> {
                    BigDecimal b = row.get("balance", BigDecimal.class);
                    return b != null ? b : BigDecimal.ZERO;
                })
                .one()
                .defaultIfEmpty(BigDecimal.ZERO)
                .onErrorResume(err -> {
                    log.warn("[member-balance] lookup failed for member {} currency {}: {} — defaulting to zero",
                            memberId, currencyCode, err.getMessage());
                    return Mono.just(BigDecimal.ZERO);
                });
    }
}
