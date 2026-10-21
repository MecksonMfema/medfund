package com.medfund.finance.service;

import com.medfund.finance.dto.UpsertTenantBankAccountRequest;
import com.medfund.finance.entity.TenantBankAccount;
import com.medfund.finance.repository.TenantBankAccountRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantBankAccountService {

    private final TenantBankAccountRepository repository;
    private final AuditPublisher auditPublisher;

    public Flux<TenantBankAccount> findAll() {
        return repository.findAllOrdered();
    }

    public Flux<TenantBankAccount> findByCurrency(String currencyCode) {
        return repository.findByCurrencyCode(currencyCode);
    }

    public Mono<TenantBankAccount> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)));
    }

    @Transactional
    public Mono<TenantBankAccount> create(UpsertTenantBankAccountRequest request, String actor, String actorEmail) {
        var account = new TenantBankAccount();
        applyFields(account, request);
        boolean nominated = Boolean.TRUE.equals(request.nominated());
        account.setNominated(nominated);
        return repository.save(account)
            .flatMap(saved -> nominated
                ? repository.clearNominationsForCurrencyExcept(saved.getCurrencyCode(), saved.getId())
                    .thenReturn(saved)
                : Mono.just(saved))
            .flatMap(saved -> publishAudit("CREATE", saved.getId(), saved.getLabel(),
                    null, snapshot(saved), actor, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantBankAccount> update(UUID id, UpsertTenantBankAccountRequest request, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)))
            .flatMap(existing -> {
                Map<String, Object> before = snapshot(existing);
                applyFields(existing, request);
                boolean nominated = Boolean.TRUE.equals(request.nominated());
                existing.setNominated(nominated);
                return (nominated
                    ? repository.clearNominationsForCurrencyExcept(existing.getCurrencyCode(), existing.getId())
                        .then(repository.save(existing))
                    : repository.save(existing))
                    .flatMap(saved -> publishAudit("UPDATE", saved.getId(), saved.getLabel(),
                            before, snapshot(saved), actor, actorEmail).thenReturn(saved));
            });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)))
            .flatMap(existing -> repository.deleteById(id)
                .then(publishAudit("DELETE", id, existing.getLabel(),
                        snapshot(existing), null, actor, actorEmail)));
    }

    private void applyFields(TenantBankAccount account, UpsertTenantBankAccountRequest request) {
        account.setBankName(request.bankName());
        account.setAccountNumber(request.accountNumber());
        account.setBranchCode(request.branchCode());
        account.setSwiftCode(request.swiftCode());
        account.setAccountName(request.accountName());
        account.setCurrencyCode(request.currencyCode());
        account.setLabel(request.label());
        account.setNotes(request.notes());
        account.setActive(request.active() == null ? Boolean.TRUE : request.active());
    }

    private Map<String, Object> snapshot(TenantBankAccount a) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("label",         a.getLabel());
        snap.put("bankName",      a.getBankName());
        snap.put("accountNumber", a.getAccountNumber());
        snap.put("branchCode",    a.getBranchCode());
        snap.put("swiftCode",     a.getSwiftCode());
        snap.put("accountName",   a.getAccountName());
        snap.put("currencyCode",  a.getCurrencyCode());
        snap.put("notes",         a.getNotes());
        snap.put("nominated",     a.getNominated());
        snap.put("active",        a.getActive());
        return snap;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
            .filter(k -> !Objects.equals(before.get(k), after.get(k)))
            .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, UUID id, String entityName,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actor, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "TenantBankAccount",
                id.toString(),
                entityName,
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                diff(before, after),
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
