package com.medfund.finance.repository;

import com.medfund.finance.dto.MemberBalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side detail query for GET /api/v1/creditors/member/{memberId}.
 * A member can hold multiple {@code member_balances} rows (one per
 * currency), so the response is a {@link Flux}. Joins {@code members}
 * for name/code/email so the detail-page summary card renders inline.
 */
@Repository
@RequiredArgsConstructor
public class MemberBalanceQueryRepository {

    private final DatabaseClient db;

    public Flux<MemberBalanceResponse> findByMemberId(UUID memberId) {
        return db.sql("""
                SELECT b.id, b.member_id, b.total_claimed, b.total_approved,
                       b.total_paid, b.outstanding_balance, b.currency_code,
                       b.last_updated_at, b.created_at,
                       CONCAT_WS(' ', m.first_name, m.last_name) AS member_name,
                       m.member_number AS member_code,
                       m.email         AS member_email
                  FROM member_balances b
                  LEFT JOIN members m ON m.id = b.member_id
                 WHERE b.member_id = :memberId
                 ORDER BY b.currency_code
                """)
                .bind("memberId", memberId)
                .map((row, meta) -> new MemberBalanceResponse(
                        row.get("id",                  UUID.class),
                        row.get("member_id",           UUID.class),
                        row.get("member_name",         String.class),
                        row.get("member_code",         String.class),
                        row.get("member_email",        String.class),
                        row.get("total_claimed",       BigDecimal.class),
                        row.get("total_approved",      BigDecimal.class),
                        row.get("total_paid",          BigDecimal.class),
                        row.get("outstanding_balance", BigDecimal.class),
                        row.get("currency_code",       String.class),
                        row.get("last_updated_at",     Instant.class),
                        row.get("created_at",          Instant.class)))
                .all();
    }
}
