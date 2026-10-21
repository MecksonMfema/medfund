package com.medfund.claims.client;

import com.medfund.claims.client.MemberLookupClient.DependantSummary;
import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice IT for the {@link MemberLookupClient#findDependantById(UUID)}
 * SQL wiring introduced for the eligibility-quote dependant flow.
 *
 * <p>Guards the query against the real V001 + V036 shape (id, member_id,
 * member_number, first/last_name, status, date_of_birth) plus the
 * tenant search-path — the SIU IT stack is the closest existing template
 * (slim, tenant-scoped, additive test-migration).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(MemberLookupClientDependantIT.SecurityStub.class)
@WithTenant("00000000-0000-4000-8000-000000000001")
class MemberLookupClientDependantIT extends AbstractPostgresIntegrationTest {

    @Autowired private MemberLookupClient client;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM dependants").fetch().rowsUpdated().block(Duration.ofSeconds(5));
        db.sql("DELETE FROM members").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void findDependantById_returnsSummaryWithSponsorAndOwnMemberNumber() {
        UUID sponsorId = seedMember("Sponsor", "One", "M-100");
        UUID dependantId = seedDependant(sponsorId, "D-100", "Junior", "One", "active");

        DependantSummary summary = client.findDependantById(dependantId)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(summary).isNotNull();
        assertThat(summary.id()).isEqualTo(dependantId);
        assertThat(summary.memberId()).isEqualTo(sponsorId);
        assertThat(summary.memberNumber()).isEqualTo("D-100");
        assertThat(summary.firstName()).isEqualTo("Junior");
        assertThat(summary.lastName()).isEqualTo("One");
        assertThat(summary.status()).isEqualTo("active");
        assertThat(summary.dateOfBirth()).isEqualTo(LocalDate.of(2015, 6, 1));
    }

    @Test
    void findDependantById_returnsEmptyForUnknownId() {
        DependantSummary summary = client.findDependantById(UUID.randomUUID())
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(summary).isNull();
    }

    @Test
    void findDependantById_handlesNullMemberNumberRow() {
        UUID sponsorId = seedMember("Sponsor", "Two", "M-101");
        UUID dependantId = seedDependantWithoutMemberNumber(sponsorId, "Legacy", "Dep");

        DependantSummary summary = client.findDependantById(dependantId)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));

        assertThat(summary).isNotNull();
        assertThat(summary.memberNumber()).isNull();
        assertThat(summary.firstName()).isEqualTo("Legacy");
    }

    private UUID seedMember(String first, String last, String memberNumber) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO members (id, first_name, last_name, member_number, status)
                VALUES (:id, :f, :l, :mn, 'active')
                """)
                .bind("id", id).bind("f", first).bind("l", last).bind("mn", memberNumber)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));
        return id;
    }

    private UUID seedDependant(UUID memberId, String memberNumber,
                                String first, String last, String status) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO dependants (id, member_id, member_number, first_name, last_name,
                                        date_of_birth, status)
                VALUES (:id, :mid, :mn, :f, :l, :dob, :status)
                """)
                .bind("id", id).bind("mid", memberId).bind("mn", memberNumber)
                .bind("f", first).bind("l", last)
                .bind("dob", LocalDate.of(2015, 6, 1))
                .bind("status", status)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));
        return id;
    }

    private UUID seedDependantWithoutMemberNumber(UUID memberId, String first, String last) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO dependants (id, member_id, first_name, last_name, date_of_birth)
                VALUES (:id, :mid, :f, :l, :dob)
                """)
                .bind("id", id).bind("mid", memberId)
                .bind("f", first).bind("l", last)
                .bind("dob", LocalDate.of(2010, 1, 1))
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));
        return id;
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "it", "iss", "it", "email", "it@medfund.local")));
        }
    }
}
