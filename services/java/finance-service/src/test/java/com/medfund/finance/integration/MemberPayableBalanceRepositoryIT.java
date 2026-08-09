package com.medfund.finance.integration;

import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.MemberPayableApplication;
import com.medfund.finance.repository.MemberPayableApplicationRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository.OutstandingMemberPayable;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-level IT exercising the derived-aggregate math in
 * {@link MemberPayableBalanceRepository}. Mixed data — multiple payables in
 * two currencies, partial applications, and a fully-consumed payable — and
 * asserts the CTE returns the correct per-currency remaining. Same
 * Testcontainers harness as {@link CtcPaymentServiceIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(MemberPayableBalanceRepositoryIT.SecurityStub.class)
class MemberPayableBalanceRepositoryIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000002";

    @Autowired private MemberPayableRepository payables;
    @Autowired private MemberPayableApplicationRepository apps;
    @Autowired private MemberPayableBalanceRepository balance;

    @Test
    @WithTenant(TENANT_ID)
    void findOutstandingByMember_computesPerCurrencyBalancesFromCteMath() {
        UUID memberId = UUID.randomUUID();

        // $150 USD payable, $30 already applied (partial CTC).
        MemberPayable usdPartial = insertPayable(memberId, "USD", new BigDecimal("150.00"), "open");
        insertApplication(usdPartial.getId(), new BigDecimal("30.00"), "USD");

        // $200 USD payable, untouched.
        insertPayable(memberId, "USD", new BigDecimal("200.00"), "open");

        // R500 ZAR payable, fully consumed (status flipped to applied).
        MemberPayable zarFull = insertPayable(memberId, "ZAR", new BigDecimal("500.00"), "applied");
        insertApplication(zarFull.getId(), new BigDecimal("500.00"), "ZAR");

        // R300 ZAR payable, still open.
        insertPayable(memberId, "ZAR", new BigDecimal("300.00"), "open");

        // Reversed payable — must be excluded from totals.
        insertPayable(memberId, "USD", new BigDecimal("999.00"), "reversed");

        List<OutstandingMemberPayable> rows = balance.findOutstandingByMember(memberId)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(rows).isNotNull();
        // USD = (150 + 200) - 30 = 320. ZAR = (500 + 300) - 500 = 300.
        assertThat(rows).extracting("currencyCode", "outstanding")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("USD", new BigDecimal("320.0000")),
                        org.assertj.core.groups.Tuple.tuple("ZAR", new BigDecimal("300.0000")));
    }

    @Test
    @WithTenant(TENANT_ID)
    void remainingOn_singlePayable_subtractsApplications() {
        UUID memberId = UUID.randomUUID();
        MemberPayable p = insertPayable(memberId, "USD", new BigDecimal("100.00"), "open");
        insertApplication(p.getId(), new BigDecimal("40.00"), "USD");
        insertApplication(p.getId(), new BigDecimal("25.00"), "USD");

        BigDecimal remaining = balance.remainingOn(p.getId())
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertThat(remaining).isEqualByComparingTo("35.00");
    }

    @Test
    @WithTenant(TENANT_ID)
    void remainingOn_reversedApplication_addsBackToRemaining() {
        // A CTC commit writes +amount; a CTC reversal writes -amount. The
        // remaining balance should return to the pre-CTC value.
        UUID memberId = UUID.randomUUID();
        MemberPayable p = insertPayable(memberId, "USD", new BigDecimal("80.00"), "open");
        insertApplication(p.getId(), new BigDecimal("80.00"), "USD");   // committed
        insertApplication(p.getId(), new BigDecimal("-80.00"), "USD");  // reversed

        BigDecimal remaining = balance.remainingOn(p.getId())
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        assertThat(remaining).isEqualByComparingTo("80.00");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private MemberPayable insertPayable(UUID memberId, String currency, BigDecimal amount, String status) {
        MemberPayable p = new MemberPayable();
        p.setMemberId(memberId);
        p.setClaimId(UUID.randomUUID());
        p.setClaimNumber("CLM-" + System.nanoTime());
        p.setAmount(amount);
        p.setCurrencyCode(currency);
        p.setStatus(status);
        p.setRecordedAt(Instant.now());
        MemberPayable saved = payables.save(p)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(saved).isNotNull();
        return saved;
    }

    private void insertApplication(UUID payableId, BigDecimal amount, String currency) {
        MemberPayableApplication a = new MemberPayableApplication();
        a.setMemberPayableId(payableId);
        a.setSourceType("CTC");
        a.setSourceId(UUID.randomUUID());
        a.setAmountApplied(amount);
        a.setCurrencyCode(currency);
        a.setAppliedAt(Instant.now());
        apps.save(a)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
    }
}
