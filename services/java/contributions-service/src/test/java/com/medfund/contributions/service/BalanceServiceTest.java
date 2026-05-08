package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.entity.GroupRunningBalance;
import com.medfund.contributions.entity.MemberRunningBalance;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.BalanceQueryRepository;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.repository.GroupRunningBalanceRepository;
import com.medfund.contributions.repository.MemberRunningBalanceRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock private MemberRunningBalanceRepository memberRepo;
    @Mock private GroupRunningBalanceRepository groupRepo;
    @Mock private BalanceQueryRepository queryRepo;
    @Mock private DunningConfigRepository dunningRepo;
    @Mock private DatabaseClient db;
    @Mock private DatabaseClient.GenericExecuteSpec spec;
    @Mock private AuditPublisher auditPublisher;
    @Mock private BalanceEventPublisher eventPublisher;

    private BalanceService service;

    @BeforeEach
    void setup() {
        service = new BalanceService(memberRepo, groupRepo, queryRepo, dunningRepo, db,
                auditPublisher, eventPublisher);
    }

    @Test
    void applyContributionDebit_skipsWhenAmountIsNull() {
        Contribution c = new Contribution();
        c.setMemberId(UUID.randomUUID());
        // amount and currency intentionally null
        StepVerifier.create(service.applyContributionDebit(c)).verifyComplete();
        verifyNoInteractions(db, auditPublisher, eventPublisher);
    }

    @Test
    void applyContributionDebit_skipsWhenMemberIdIsNull() {
        Contribution c = new Contribution();
        c.setAmount(BigDecimal.TEN);
        c.setCurrencyCode("USD");
        // memberId null
        StepVerifier.create(service.applyContributionDebit(c)).verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    void applyTransaction_noOwners_isNoOp() {
        Transaction t = new Transaction();
        t.setAmount(BigDecimal.TEN);
        t.setCurrencyCode("USD");
        StepVerifier.create(service.applyTransaction(t, "+", null, null)).verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    void getMemberBalance_returnsZeroWhenRowMissing() {
        UUID memberId = UUID.randomUUID();
        when(memberRepo.findByMemberAndCurrency(memberId, "USD")).thenReturn(Mono.empty());

        StepVerifier.create(service.getMemberBalance(memberId, "USD"))
                .assertNext(resp -> {
                    assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(resp.memberId()).isEqualTo(memberId);
                    assertThat(resp.currencyCode()).isEqualTo("USD");
                })
                .verifyComplete();
    }

    @Test
    void getGroupBalance_returnsZeroWhenRowMissing() {
        UUID groupId = UUID.randomUUID();
        when(groupRepo.findByGroupAndCurrency(groupId, "ZAR")).thenReturn(Mono.empty());

        StepVerifier.create(service.getGroupBalance(groupId, "ZAR"))
                .assertNext(resp -> {
                    assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(resp.groupId()).isEqualTo(groupId);
                })
                .verifyComplete();
    }

    @Test
    void getMemberBalance_returnsRowWhenPresent() {
        UUID memberId = UUID.randomUUID();
        MemberRunningBalance row = new MemberRunningBalance();
        row.setMemberId(memberId);
        row.setCurrencyCode("USD");
        row.setBalance(new BigDecimal("250.00"));
        row.setLastChargeAt(Instant.now());
        when(memberRepo.findByMemberAndCurrency(memberId, "USD")).thenReturn(Mono.just(row));

        StepVerifier.create(service.getMemberBalance(memberId, "USD"))
                .assertNext(resp -> assertThat(resp.balance()).isEqualByComparingTo("250.00"))
                .verifyComplete();
    }
}
