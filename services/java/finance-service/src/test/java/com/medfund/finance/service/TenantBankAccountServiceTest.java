package com.medfund.finance.service;

import com.medfund.finance.dto.UpsertTenantBankAccountRequest;
import com.medfund.finance.entity.TenantBankAccount;
import com.medfund.finance.repository.TenantBankAccountRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantBankAccountServiceTest {

    @Mock
    private TenantBankAccountRepository repository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private TenantBankAccountService service;

    @Test
    void create_nominated_clearsOtherNominationsForSameCurrency() {
        var request = sampleRequest("USD", true);
        when(repository.save(any())).thenAnswer(inv -> {
            TenantBankAccount saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            saved.setCreatedAt(Instant.now());
            return Mono.just(saved);
        });
        when(repository.clearNominationsForCurrencyExcept(eq("USD"), any())).thenReturn(Mono.just(0));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getNominated()).isTrue();
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getActive()).isTrue();
                    assertThat(saved.getLabel()).isEqualTo("Ops USD");
                    assertThat(saved.getNotes()).isEqualTo("Primary payout account");
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(repository).clearNominationsForCurrencyExcept(eq("USD"), any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_notNominated_doesNotClearOthers() {
        var request = sampleRequest("USD", false);
        when(repository.save(any())).thenAnswer(inv -> {
            TenantBankAccount saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getNominated()).isFalse())
                .verifyComplete();

        verify(repository).save(any());
        verify(repository, never()).clearNominationsForCurrencyExcept(anyString(), any());
    }

    @Test
    void update_flippingNominated_clearsOthers_andEmitsChangedFields() {
        var existing = existingAccount("USD", false);
        var request = sampleRequest("USD", true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.clearNominationsForCurrencyExcept(eq("USD"), eq(existing.getId())))
            .thenReturn(Mono.just(1));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.update(existing.getId(), request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getNominated()).isTrue())
                .verifyComplete();

        verify(repository).clearNominationsForCurrencyExcept(eq("USD"), eq(existing.getId()));
        verify(repository).save(any());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo("UPDATE");
        assertThat(event.entityType()).isEqualTo("TenantBankAccount");
        assertThat(event.entityName()).isEqualTo("Ops USD");
        // Existing was: bankName=Old Bank, accountNumber=0000000000, accountName=Old Account,
        // notes=null, nominated=false; request switches every one of these plus branch/swift.
        assertThat(event.changedFields())
            .isNotEmpty()
            .contains("bankName", "accountNumber", "accountName", "label", "notes", "nominated");
    }

    @Test
    void delete_existing_removesAndAudits() {
        var existing = existingAccount("USD", false);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.deleteById(existing.getId())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.delete(existing.getId(), "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .verifyComplete();

        verify(repository).deleteById(existing.getId());
        verify(auditPublisher, times(1)).publish(any());
    }

    @Test
    void delete_missing_errors() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Mono.empty());

        StepVerifier.create(
                service.delete(missingId, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).deleteById(any(UUID.class));
    }

    // ---- Helpers ----

    private UpsertTenantBankAccountRequest sampleRequest(String currency, boolean nominated) {
        return new UpsertTenantBankAccountRequest(
            "Test Bank", "1234567890", "0001", "TESTSWIFT",
            "MedFund Operations", currency, "Ops USD", "Primary payout account",
            nominated, true);
    }

    private TenantBankAccount existingAccount(String currency, boolean nominated) {
        var a = new TenantBankAccount();
        a.setId(UUID.randomUUID());
        a.setBankName("Old Bank");
        a.setAccountNumber("0000000000");
        a.setAccountName("Old Account");
        a.setCurrencyCode(currency);
        a.setLabel("Old Label");
        a.setNotes(null);
        a.setNominated(nominated);
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        return a;
    }
}
