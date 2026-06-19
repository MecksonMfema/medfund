package com.medfund.finance.service;

import com.medfund.finance.dto.UpsertMascaBankAccountRequest;
import com.medfund.finance.entity.MascaBankAccount;
import com.medfund.finance.repository.MascaBankAccountRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class MascaBankAccountServiceTest {

    @Mock
    private MascaBankAccountRepository repository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private MascaBankAccountService service;

    @Test
    void create_nominated_clearsOtherNominationsForSameCurrency() {
        var request = sampleRequest("USD", true);
        when(repository.save(any())).thenAnswer(inv -> {
            MascaBankAccount saved = inv.getArgument(0);
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
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(repository).clearNominationsForCurrencyExcept(eq("USD"), any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_notNominated_doesNotClearOthers() {
        var request = sampleRequest("USD", false);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
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
    void update_flippingNominated_clearsOthers() {
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

    private UpsertMascaBankAccountRequest sampleRequest(String currency, boolean nominated) {
        return new UpsertMascaBankAccountRequest(
            "Test Bank", "1234567890", "0001", "TESTSWIFT",
            "MedFund Operations", currency, nominated, true);
    }

    private MascaBankAccount existingAccount(String currency, boolean nominated) {
        var a = new MascaBankAccount();
        a.setId(UUID.randomUUID());
        a.setBankName("Old Bank");
        a.setAccountNumber("0000000000");
        a.setAccountName("Old Account");
        a.setCurrencyCode(currency);
        a.setNominated(nominated);
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        return a;
    }
}
