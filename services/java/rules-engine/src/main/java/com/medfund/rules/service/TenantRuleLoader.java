package com.medfund.rules.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.entity.TenantRule;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.repository.TenantRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Reusable loader that ensures a tenant's rules are compiled into the local
 * {@link TenantRuleEngine} the first time the host service evaluates anything
 * for that tenant. Each consuming microservice (claims, contributions,
 * finance, etc.) ships its own engine instance — this class is the lazy
 * bridge from the {@code public.tenant_rules} table to that local cache.
 *
 * <p>Idempotent: calls after the first one for a given tenant are cheap
 * (the engine reports {@code hasRulesLoaded == true} and the loader returns
 * immediately). Cache busting is via {@link #invalidate(UUID)} or the
 * {@code TenantRuleEngine.removeRules(...)} hook directly.
 *
 * <p>Why this lives in {@code rules-engine}: every consumer that wants to
 * call the engine already depends on this module for the entity / repo /
 * engine classes — adding the loader here keeps the rules infrastructure
 * in one place. Consumers just inject and call.
 */
@Slf4j
@Component
public class TenantRuleLoader {

    private final TenantRuleRepository repository;
    private final TenantRuleEngine     engine;
    private final ObjectMapper         objectMapper;

    public TenantRuleLoader(TenantRuleRepository repository,
                            TenantRuleEngine engine,
                            ObjectMapper objectMapper) {
        this.repository   = repository;
        this.engine       = engine;
        this.objectMapper = objectMapper;
    }

    /**
     * Ensure the engine has the tenant's enabled rules loaded. No-op if the
     * tenant's KieContainer already exists in the engine cache.
     */
    public Mono<Void> ensureLoaded(UUID tenantId) {
        if (tenantId == null) return Mono.empty();
        if (engine.hasRulesLoaded(tenantId.toString())) return Mono.empty();
        return forceReload(tenantId);
    }

    /**
     * Discard the cached KieContainer for the tenant. Next call to
     * {@link #ensureLoaded(UUID)} will rebuild from the database.
     * Called after a rule mutation event (e.g. Kafka {@code medfund.rules.updated}).
     */
    public void invalidate(UUID tenantId) {
        if (tenantId == null) return;
        engine.removeRules(tenantId.toString());
    }

    /**
     * Force a rebuild of the tenant's KieContainer from the current rule set.
     * Bypasses the {@code hasRulesLoaded} short-circuit — useful for the
     * initial load and the resync-after-invalidate cases.
     */
    public Mono<Void> forceReload(UUID tenantId) {
        if (tenantId == null) return Mono.empty();
        return repository.findEnabledByTenant(tenantId)
                .map(this::deserialize)
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(defs -> {
                    log.debug("[rules-loader] Loading {} enabled rules for tenant {}", defs.size(), tenantId);
                    engine.reloadRules(tenantId.toString(), defs);
                })
                .then();
    }

    private RuleDefinition deserialize(TenantRule rule) {
        try {
            RuleDefinition def = objectMapper.readValue(rule.getDefinition(), RuleDefinition.class);
            def.setId(rule.getId().toString());
            def.setName(rule.getName());
            def.setCategory(rule.getCategory());
            def.setPriority(rule.getPriority() != null ? rule.getPriority() : 50);
            def.setEnabled(Boolean.TRUE.equals(rule.getEnabled()));
            def.setVersion(rule.getVersion() != null ? rule.getVersion() : 1);
            return def;
        } catch (JsonProcessingException e) {
            // Should never happen — the same JSON went in via TenantRuleService.serialize.
            // Throw a runtime so the caller's reactor pipeline aborts cleanly rather than
            // silently dropping a corrupt rule from the loaded set.
            throw new IllegalStateException("Stored rule " + rule.getId()
                    + " has corrupt definition JSON: " + e.getMessage(), e);
        }
    }
}
