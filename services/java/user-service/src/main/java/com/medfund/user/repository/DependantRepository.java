package com.medfund.user.repository;

import com.medfund.user.entity.Dependant;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DependantRepository extends R2dbcRepository<Dependant, UUID> {

    @Query("SELECT * FROM dependants WHERE member_id = :memberId ORDER BY first_name")
    Flux<Dependant> findByMemberId(UUID memberId);

    @Query("SELECT * FROM dependants WHERE member_id = :memberId AND status = :status")
    Flux<Dependant> findByMemberIdAndStatus(UUID memberId, String status);

    /**
     * Case-insensitive substring search on first name, last name, or
     * member number. Mirrors {@code MemberRepository.search} so the
     * unified beneficiary search endpoint composes results from both
     * tables with the same matching rules.
     */
    @Query("""
            SELECT * FROM dependants
             WHERE LOWER(first_name)  LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(last_name)   LIKE LOWER(CONCAT('%', :query, '%'))
                OR member_number      LIKE CONCAT('%', :query, '%')
             ORDER BY last_name, first_name
            """)
    Flux<Dependant> search(String query);

    /**
     * Existence check for the new {@code member_number} column. Mirrors
     * {@code MemberRepository.existsByMemberNumber} so the cross-table
     * uniqueness guard in MemberNumberService can OR the two repos.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM dependants WHERE member_number = :memberNumber)")
    Mono<Boolean> existsByMemberNumber(String memberNumber);

    /**
     * Highest numeric suffix currently in use for dependants under the
     * given member, when the tenant uses the SHARED_WITH_SUFFIX scheme
     * (parent base + "-NN"). Returns 1 (the member's own suffix) when no
     * dependants exist yet so the next-assigned suffix is 2.
     *
     * <p>The {@code basePrefix} comes from stripping the member's own
     * "-01" suffix off its {@code member_number}; e.g. for member
     * "MBR-001234-01" the basePrefix is "MBR-001234".
     */
    @Query("""
            SELECT COALESCE(MAX(NULLIF(SUBSTRING(member_number FROM '-([0-9]+)$'), '')::int), 1)
              FROM dependants
             WHERE member_number IS NOT NULL
               AND member_number LIKE :basePrefix || '-%'
            """)
    Mono<Integer> maxSuffixForBase(String basePrefix);
}
