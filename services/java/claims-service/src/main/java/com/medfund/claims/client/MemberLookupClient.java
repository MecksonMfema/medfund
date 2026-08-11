package com.medfund.claims.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Look up a member from the tenant {@code members} table by their friendly
 * {@code member_number}. Used by
 * {@link com.medfund.claims.service.EligibilityQuoteService} to resolve a
 * provider-supplied policy identifier without ever exposing raw member UUIDs
 * on the wire (per {@code feedback_no_raw_id_inputs}).
 *
 * <p>Reads via {@link DatabaseClient} on the tenant search-path — the query is
 * unqualified per {@code bug_public_prefix_silent_rollback}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberLookupClient {

    private final DatabaseClient databaseClient;

    public Mono<MemberSummary> findByMemberNumber(String memberNumber) {
        if (memberNumber == null || memberNumber.isBlank()) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                SELECT id, member_number, first_name, last_name,
                       status, suspend_reason, scheme_id, group_id,
                       enrollment_date, termination_date
                  FROM members
                 WHERE member_number = :memberNumber
                 LIMIT 1
                """)
                .bind("memberNumber", memberNumber)
                .map((row, meta) -> new MemberSummary(
                        row.get("id", UUID.class),
                        row.get("member_number", String.class),
                        row.get("first_name", String.class),
                        row.get("last_name", String.class),
                        row.get("status", String.class),
                        row.get("suspend_reason", String.class),
                        row.get("scheme_id", UUID.class),
                        row.get("group_id", UUID.class),
                        row.get("enrollment_date", LocalDate.class),
                        row.get("termination_date", LocalDate.class)))
                .one();
    }

    /**
     * Slim view of a member row — just what the quote flow needs. Full member
     * profile stays in user-service; this reader exists so the adjudication
     * hot path never has to make an outbound REST call to resolve a policy
     * number.
     */
    public record MemberSummary(
            UUID id,
            String memberNumber,
            String firstName,
            String lastName,
            String status,
            String suspendReason,
            UUID schemeId,
            UUID groupId,
            LocalDate enrollmentDate,
            LocalDate terminationDate) {
    }
}
