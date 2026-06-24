package com.medfund.finance.service;

import com.medfund.finance.dto.UpsertMascaBankAccountRequest;
import com.medfund.finance.entity.MascaBankAccount;
import com.medfund.finance.repository.MascaBankAccountRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MascaBankAccountService {

    private final MascaBankAccountRepository repository;
    private final AuditPublisher auditPublisher;

    public Flux<MascaBankAccount> findAll() {
        return repository.findAllOrdered();
    }

    public Flux<MascaBankAccount> findByCurrency(String currencyCode) {
        return repository.findByCurrencyCode(currencyCode);
    }

    public Mono<MascaBankAccount> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)));
    }

    @Transactional
    public Mono<MascaBankAccount> create(UpsertMascaBankAccountRequest request, String actor, String actorEmail) {
        var account = new MascaBankAccount();
        applyFields(account, request);
        boolean nominated = Boolean.TRUE.equals(request.nominated());
        account.setNominated(nominated);
        return repository.save(account)
            .flatMap(saved -> nominated
                ? repository.clearNominationsForCurrencyExcept(saved.getCurrencyCode(), saved.getId())
                    .thenReturn(saved)
                : Mono.just(saved))
            .flatMap(saved -> publishAudit("CREATE", saved.getId(), saved.getAccountName(), null, snapshot(saved), actor, actorEmail)
                .thenReturn(saved));
    }

    @Transactional
    public Mono<MascaBankAccount> update(UUID id, UpsertMascaBankAccountRequest request, String actor, String actorEmail) {
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
                    .flatMap(saved -> publishAudit("UPDATE", saved.getId(), saved.getAccountName(), before, snapshot(saved), actor, actorEmail)
                        .thenReturn(saved));
            });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)))
            .flatMap(existing -> repository.deleteById(id)
                .then(publishAudit("DELETE", id, existing.getAccountName(), snapshot(existing), null, actor, actorEmail)));
    }

    private void applyFields(MascaBankAccount account, UpsertMascaBankAccountRequest request) {
        account.setBankName(request.bankName());
        account.setAccountNumber(request.accountNumber());
        account.setBranchCode(request.branchCode());
        account.setSwiftCode(request.swiftCode());
        account.setAccountName(request.accountName());
        account.setCurrencyCode(request.currencyCode());
        account.setActive(request.active() == null ? Boolean.TRUE : request.active());
    }

    private Map<String, Object> snapshot(MascaBankAccount a) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("bankName", a.getBankName());
        snap.put("accountNumber", a.getAccountNumber());
        snap.put("currencyCode", a.getCurrencyCode());
        snap.put("nominated", a.getNominated());
        snap.put("active", a.getActive());
        return snap;
    }

    private Mono<Void> publishAudit(String action, UUID id, String entityName, Map<String, Object> before, Map<String, Object> after, String actor, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "MascaBankAccount",
                id.toString(),
                entityName,
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
