package com.medfund.finance.service;

import com.medfund.finance.entity.PaymentRunItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRunFactBuilderTest {

    @Mock private DatabaseClient db;
    @Mock private DatabaseClient.GenericExecuteSpec spec;
    @Mock private FetchSpec<java.util.Map<String, Object>> fetch;

    @Test
    void build_memberItem_dispatchesToMemberBranch_noNpe() {
        var builder = new PaymentRunFactBuilder(db);
        UUID memberId = UUID.randomUUID();
        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(UUID.randomUUID());
        item.setPayeeType("MEMBER");
        item.setMemberId(memberId);
        item.setAmount(new BigDecimal("100.00"));
        item.setCurrencyCode("USD");
        item.setStatus("pending");

        stubEmptyDbClient();

        StepVerifier.create(builder.build(item, BigDecimal.ZERO))
            .assertNext(facts -> {
                assertThat(facts.paymentRun().getMemberId()).isEqualTo(memberId.toString());
                assertThat(facts.paymentRun().getProviderId()).isNull();
                assertThat(facts.paymentRun().getPayeeType()).isEqualTo("MEMBER");
            })
            .verifyComplete();

        // Verify member-branch SQL was invoked
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(db, org.mockito.Mockito.atLeastOnce()).sql(sqlCap.capture());
        assertThat(sqlCap.getAllValues())
            .anyMatch(sql -> sql.contains("FROM members"))
            .anyMatch(sql -> sql.contains("pri.member_id"))
            .anyMatch(sql -> sql.contains("member_id = :id"));
    }

    @Test
    void build_providerItem_dispatchesToProviderBranch() {
        var builder = new PaymentRunFactBuilder(db);
        UUID providerId = UUID.randomUUID();
        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPaymentRunId(UUID.randomUUID());
        item.setPayeeType("PROVIDER");
        item.setProviderId(providerId);
        item.setAmount(new BigDecimal("200.00"));
        item.setCurrencyCode("USD");
        item.setStatus("pending");

        stubEmptyDbClient();

        StepVerifier.create(builder.build(item, new BigDecimal("50.00")))
            .assertNext(facts -> {
                assertThat(facts.paymentRun().getProviderId()).isEqualTo(providerId.toString());
                assertThat(facts.paymentRun().getMemberId()).isNull();
                assertThat(facts.paymentRun().getPayeeType()).isEqualTo("PROVIDER");
                assertThat(facts.paymentRun().getAdvancePaid()).isEqualByComparingTo("50.00");
            })
            .verifyComplete();

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(db, org.mockito.Mockito.atLeastOnce()).sql(sqlCap.capture());
        assertThat(sqlCap.getAllValues())
            .anyMatch(sql -> sql.contains("FROM public.providers"))
            .anyMatch(sql -> sql.contains("pri.provider_id"))
            .anyMatch(sql -> sql.contains("provider_id = :id"));
    }

    @Test
    void build_memberItemWithoutMemberId_shortCircuits() {
        var builder = new PaymentRunFactBuilder(db);
        var item = new PaymentRunItem();
        item.setId(UUID.randomUUID());
        item.setPayeeType("MEMBER");
        item.setMemberId(null);
        item.setAmount(new BigDecimal("100.00"));
        item.setCurrencyCode("USD");
        item.setStatus("pending");

        StepVerifier.create(builder.build(item, BigDecimal.ZERO))
            .assertNext(facts -> assertThat(facts.paymentRun().getPayeeType()).isEqualTo("MEMBER"))
            .verifyComplete();

        verify(db, org.mockito.Mockito.times(0)).sql(anyString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubEmptyDbClient() {
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn((FetchSpec) fetch);
        when(fetch.one()).thenReturn(Mono.empty());
    }
}
