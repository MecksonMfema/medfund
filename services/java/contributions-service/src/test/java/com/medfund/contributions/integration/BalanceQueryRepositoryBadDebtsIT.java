package com.medfund.contributions.integration;

import com.medfund.contributions.dto.BalanceRow;
import com.medfund.contributions.repository.BalanceQueryRepository;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link BalanceQueryRepository#findBadDebts} /
 * {@link BalanceQueryRepository#countBadDebts}. This is the seam that
 * the unit tests can't reach — the actual SQL filter (status IN
 * {@code ('deactivated','terminated')} × {@code balance > 0} × the
 * MEMBER half's {@code group_id IS NULL} carve-out).
 *
 * <p>A regression at this layer is invisible to unit-mocked repos and
 * shows up only when a tenant opens the bad-debts page and sees the
 * wrong slice of rows. Seeds one member per relevant combination and
 * asserts exactly which subjects appear.
 *
 * <p>Uses a stripped-down schema (test-migration/bad-debts) rather than
 * the tenant-side production migrations — same rationale as
 * {@link SchemeServiceIT}: the module doesn't own them and a slice test
 * shouldn't couple to their evolution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/bad-debts-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(BalanceQueryRepositoryBadDebtsIT.SecurityStub.class)
class BalanceQueryRepositoryBadDebtsIT extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    @Autowired private BalanceQueryRepository queryRepo;
    @Autowired private DatabaseClient db;

    /**
     * Truncate + re-seed for each test so assertions stay independent.
     * Order matters — child balance rows before their parent subjects.
     */
    @BeforeEach
    void resetSchema() {
        db.sql("TRUNCATE member_running_balance, group_running_balance, "
                + "members, groups, group_liaisons, staff_users CASCADE").then()
                .block();
    }

    // ------------------------------------------------------------------
    // findBadDebts — the status / balance / group_id filter matrix.
    // Each test seeds a curated set of subjects covering both the
    // must-appear and must-not-appear branches, then asserts the
    // returned subjectIds are exactly the expected subset.
    // ------------------------------------------------------------------

    @Test
    void findBadDebts_returnsOnlyDeactivatedOrTerminatedMembersWithPositiveBalance() {
        // Seed six ungrouped members in USD, one per relevant permutation:
        //  1. active + owing              → must NOT appear (still-billable)
        //  2. suspended + owing           → must NOT appear (still-billable)
        //  3. deactivated + owing         → MUST appear
        //  4. terminated + owing          → MUST appear
        //  5. deactivated + zero balance  → must NOT appear (write-off carve-out)
        //  6. deactivated + owing but grouped → must NOT appear (grouped-member exclusion)
        //
        // Anchoring the assertion on subjectId (not just count) means a
        // future off-by-one in the filter — say, accidentally including
        // suspended rows — fails loudly rather than silently doubling the list.
        UUID activeOwing         = seedMember("active",       new BigDecimal("100"), null);
        UUID suspendedOwing      = seedMember("suspended",    new BigDecimal("100"), null);
        UUID deactivatedOwing    = seedMember("deactivated",  new BigDecimal("100"), null);
        UUID terminatedOwing     = seedMember("terminated",   new BigDecimal("100"), null);
        UUID deactivatedZeroBal  = seedMember("deactivated",  BigDecimal.ZERO,       null);
        UUID groupHost           = seedGroup("active",  BigDecimal.ZERO);
        UUID deactivatedGrouped  = seedMember("deactivated",  new BigDecimal("100"), groupHost);

        List<BalanceRow> rows = queryRepo.findBadDebts("USD", null, null, 100, 0)
                .collectList().block();
        assertThat(rows).isNotNull();

        List<UUID> ids = rows.stream().map(BalanceRow::subjectId).toList();
        assertThat(ids)
                .as("only deactivated + terminated + owing + ungrouped members appear")
                .containsExactlyInAnyOrder(deactivatedOwing, terminatedOwing)
                .doesNotContain(activeOwing, suspendedOwing, deactivatedZeroBal,
                                deactivatedGrouped);

        // Extra guard: countBadDebts must agree with findBadDebts on the
        // same filters — the two run separate SQL and a mismatch would
        // paginate incorrectly (e.g. "showing 2 of 5" when there are 2).
        Long count = queryRepo.countBadDebts("USD", null, null).block();
        assertThat(count).isEqualTo(2L);
    }

    @Test
    void findBadDebts_groupHalf_returnsOnlyDeactivatedOrTerminatedGroupsWithBalance() {
        // Group half must mirror the member half's status/balance filter.
        // No group_id-IS-NULL carve-out here — groups have no analogue.
        UUID activeOwing      = seedGroup("active",      new BigDecimal("500"));
        UUID suspendedOwing   = seedGroup("suspended",   new BigDecimal("500"));
        UUID deactivatedOwing = seedGroup("deactivated", new BigDecimal("500"));
        UUID terminatedOwing  = seedGroup("terminated",  new BigDecimal("500"));
        UUID deactivatedZero  = seedGroup("deactivated", BigDecimal.ZERO);

        List<UUID> ids = queryRepo.findBadDebts("USD", null, null, 100, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        assertThat(ids)
                .containsExactlyInAnyOrder(deactivatedOwing, terminatedOwing)
                .doesNotContain(activeOwing, suspendedOwing, deactivatedZero);
    }

    @Test
    void findBadDebts_subjectTypeMember_dropsGroupHalf() {
        // subjectType routing: MEMBER value drops the GROUP half of the
        // union. Regression here would leak groups into the Individuals
        // tab (or vice-versa on GROUP), breaking the tab labels' promise.
        UUID member = seedMember("deactivated", new BigDecimal("50"), null);
        UUID group  = seedGroup("deactivated",  new BigDecimal("500"));

        List<UUID> ids = queryRepo.findBadDebts("USD", "MEMBER", null, 100, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        assertThat(ids).containsExactly(member).doesNotContain(group);
    }

    @Test
    void findBadDebts_subjectTypeGroup_dropsMemberHalf() {
        UUID member = seedMember("deactivated", new BigDecimal("50"), null);
        UUID group  = seedGroup("deactivated",  new BigDecimal("500"));

        List<UUID> ids = queryRepo.findBadDebts("USD", "GROUP", null, 100, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        assertThat(ids).containsExactly(group).doesNotContain(member);
    }

    @Test
    void findBadDebts_currencyFilterExcludesOtherCurrencies() {
        // Multi-currency tenant: only rows in the requested currency
        // must come back. A regression here would leak, e.g., USD rows
        // onto a tenant's ZAR tab.
        UUID usdMember = seedMember("deactivated", new BigDecimal("100"), null);
        UUID zarMember = seedMemberWithCurrency("deactivated", new BigDecimal("100"), "ZAR", null);

        List<UUID> ids = queryRepo.findBadDebts("USD", null, null, 100, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        assertThat(ids).containsExactly(usdMember).doesNotContain(zarMember);
    }

    @Test
    void findBadDebts_searchMatchesMemberNameEmailAndCode() {
        // Search is a case-insensitive LIKE across name / email /
        // member_number. Seed one match per column and one non-match to
        // prove OR'd behaviour and case-insensitivity.
        UUID nameMatch  = seedMemberFull("Alice",  "Smith",   "alice@ex.com",  "M-001", "deactivated", null);
        UUID emailMatch = seedMemberFull("Bob",    "Jones",   "acme@zzz.com",  "M-002", "deactivated", null);
        UUID codeMatch  = seedMemberFull("Carol",  "Brown",   "carol@ex.com",  "ACME99","deactivated", null);
        UUID noMatch    = seedMemberFull("Diego",  "Vega",    "diego@ex.com",  "M-003", "deactivated", null);

        List<UUID> ids = queryRepo.findBadDebts("USD", "MEMBER", "acme", 100, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        // Two matches on lowercase "acme": the email (Bob) and the code (Carol).
        assertThat(ids).containsExactlyInAnyOrder(emailMatch, codeMatch)
                .doesNotContain(noMatch, nameMatch);
    }

    @Test
    void findBadDebts_paginationHonoursLimitAndOffset() {
        // Seed five owed rows; page through with limit=2 and prove no
        // gaps / duplicates across offsets. Balances are all identical so
        // the ORDER BY balance DESC tie-breaker (subject_id) governs
        // stability; the assertion just checks size + no overlap.
        for (int i = 0; i < 5; i++) {
            seedMember("deactivated", new BigDecimal("100"), null);
        }
        List<UUID> page1 = queryRepo.findBadDebts("USD", "MEMBER", null, 2, 0)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        List<UUID> page2 = queryRepo.findBadDebts("USD", "MEMBER", null, 2, 2)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();
        List<UUID> page3 = queryRepo.findBadDebts("USD", "MEMBER", null, 2, 4)
                .collectList().block().stream().map(BalanceRow::subjectId).toList();

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).hasSize(1);
        // No overlap across pages — critical for the "Load more" UX; the
        // three sets combined must be five distinct subjects.
        assertThat(page1).doesNotContainAnyElementsOf(page2);
        assertThat(page2).doesNotContainAnyElementsOf(page3);
        assertThat(page1).doesNotContainAnyElementsOf(page3);
    }

    @Test
    void countBadDebts_returnsUnionCardinality_notMemberOrGroupHalfOnly() {
        // Regression guard: countBadDebts wraps the base query in a
        // "SELECT COUNT(*) FROM (…) sub" — if someone accidentally
        // dropped one half of the union in the count query but not the
        // list query, pagination would round-trip inconsistent totals.
        seedMember("deactivated", new BigDecimal("100"), null);
        seedMember("terminated",  new BigDecimal("100"), null);
        seedGroup("deactivated",  new BigDecimal("500"));

        Long count = queryRepo.countBadDebts("USD", null, null).block();
        assertThat(count).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // Fixture helpers — thin INSERTs via DatabaseClient. Return the
    // generated UUID so each assertion can pin to a specific subject.
    // ------------------------------------------------------------------

    private UUID seedMember(String status, BigDecimal balance, UUID groupId) {
        return seedMemberWithCurrency(status, balance, "USD", groupId);
    }

    private UUID seedMemberWithCurrency(String status, BigDecimal balance,
                                          String currency, UUID groupId) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO members (id, first_name, last_name, email, member_number, group_id, status) "
                + "VALUES (:id, 'Test', 'Member', 'm@ex.com', :code, :gid, :status)")
                .bind("id", id)
                .bind("code", "M-" + id.toString().substring(0, 6))
                .bind("gid", groupId == null ? io.r2dbc.spi.Parameters.in(UUID.class) : io.r2dbc.spi.Parameters.in(groupId))
                .bind("status", status)
                .then().block();
        // Only seed a balance row when there's a nonzero-or-owed amount
        // to represent — the query joins on member_running_balance so a
        // missing row implicitly means "no balance in this currency."
        db.sql("INSERT INTO member_running_balance (member_id, currency_code, balance, last_charge_at, last_payment_at) "
                + "VALUES (:mid, :cur, :bal, :charge, :pay)")
                .bind("mid", id)
                .bind("cur", currency)
                .bind("bal", balance)
                .bind("charge", Instant.now().minusSeconds(60L * 60L * 24L * 60L))
                .bind("pay",    io.r2dbc.spi.Parameters.in(Instant.class))
                .then().block();
        return id;
    }

    private UUID seedMemberFull(String first, String last, String email, String code,
                                  String status, UUID groupId) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO members (id, first_name, last_name, email, member_number, group_id, status) "
                + "VALUES (:id, :first, :last, :email, :code, :gid, :status)")
                .bind("id", id)
                .bind("first", first).bind("last", last)
                .bind("email", email).bind("code", code)
                .bind("gid", groupId == null ? io.r2dbc.spi.Parameters.in(UUID.class) : io.r2dbc.spi.Parameters.in(groupId))
                .bind("status", status)
                .then().block();
        db.sql("INSERT INTO member_running_balance (member_id, currency_code, balance) "
                + "VALUES (:mid, 'USD', 100)")
                .bind("mid", id)
                .then().block();
        return id;
    }

    private UUID seedGroup(String status, BigDecimal balance) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO groups (id, name, registration_number, email, status) "
                + "VALUES (:id, :name, :reg, 'group@ex.com', :status)")
                .bind("id", id)
                .bind("name", "Grp " + id.toString().substring(0, 6))
                .bind("reg", "REG-" + id.toString().substring(0, 6))
                .bind("status", status)
                .then().block();
        db.sql("INSERT INTO group_running_balance (group_id, currency_code, balance) "
                + "VALUES (:gid, 'USD', :bal)")
                .bind("gid", id).bind("bal", balance)
                .then().block();
        return id;
    }
}
