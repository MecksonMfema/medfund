package com.medfund.rules.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.rules.dto.CreateRuleRequest;
import com.medfund.rules.dto.DryRunRequest;
import com.medfund.rules.dto.DryRunResponse;
import com.medfund.rules.dto.UpdateRuleRequest;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.entity.TenantRule;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.DependantFact;
import com.medfund.rules.fact.FamilyFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.MemberLifecycleFact;
import com.medfund.rules.fact.PaymentRunFact;
import com.medfund.rules.fact.ProviderFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.fact.TimeFact;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.repository.TenantRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant rule CRUD with hot-reload of the per-tenant {@code KieContainer}.
 * Every write path ends in {@link #reloadTenantContainer(UUID)} so the next
 * adjudication call sees the new rule set without a service restart.
 *
 * <p>Drools' {@code KieSession.fireAllRules()} is blocking, so dry-run hops
 * onto {@code Schedulers.boundedElastic()} before invoking the engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRuleService {

    private final TenantRuleRepository repository;
    private final TenantRuleEngine     engine;
    private final TenantRuleLoader     loader;
    private final RuleEventPublisher   eventPublisher;
    private final ObjectMapper         objectMapper;

    // ── Reads ─────────────────────────────────────────────────────────────────

    public Flux<TenantRule> findByTenant(UUID tenantId, String category) {
        if (category != null && !category.isBlank()) {
            return repository.findByTenantAndCategory(tenantId, category.toUpperCase());
        }
        return repository.findByTenant(tenantId);
    }

    public Mono<TenantRule> findById(UUID id, UUID tenantId) {
        return repository.findByIdAndTenant(id, tenantId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Rule not found: " + id)));
    }

    // ── Writes (each rebuilds the tenant's KieContainer) ──────────────────────

    public Mono<TenantRule> create(UUID tenantId, CreateRuleRequest req, UUID actorId) {
        return repository.existsByTenantAndKey(tenantId, req.ruleKey())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "A rule with key '" + req.ruleKey() + "' already exists for this tenant"));
                    }
                    TenantRule rule = new TenantRule();
                    rule.setTenantId(tenantId);
                    rule.setRuleKey(req.ruleKey());
                    rule.setName(req.name());
                    rule.setDescription(req.description());
                    rule.setCategory(req.category().toUpperCase());
                    rule.setTemplateId(req.templateId());
                    rule.setPriority(req.priority() != null ? req.priority() : 50);
                    rule.setDefinition(serialize(req.definition()));
                    rule.setDrlOverride(req.drlOverride());
                    rule.setEnabled(req.enabled() == null || req.enabled());
                    rule.setVersion(1);
                    rule.setCreatedBy(actorId);
                    rule.setUpdatedBy(actorId);
                    return repository.save(rule);
                })
                .flatMap(saved -> reloadTenantContainer(tenantId)
                        .then(eventPublisher.publishRuleChanged(tenantId, saved.getId(), "CREATED"))
                        .thenReturn(saved));
    }

    public Mono<TenantRule> update(UUID id, UUID tenantId, UpdateRuleRequest req, UUID actorId) {
        return findById(id, tenantId).flatMap(rule -> {
            if (req.name() != null)        rule.setName(req.name());
            if (req.description() != null) rule.setDescription(req.description());
            if (req.category() != null)    rule.setCategory(req.category().toUpperCase());
            if (req.priority() != null)    rule.setPriority(req.priority());
            if (req.definition() != null)  rule.setDefinition(serialize(req.definition()));
            if (req.drlOverride() != null) rule.setDrlOverride(req.drlOverride());
            if (req.enabled() != null)     rule.setEnabled(req.enabled());
            rule.setVersion((rule.getVersion() == null ? 1 : rule.getVersion()) + 1);
            rule.setUpdatedAt(Instant.now());
            rule.setUpdatedBy(actorId);
            return repository.save(rule);
        }).flatMap(saved -> reloadTenantContainer(tenantId)
                .then(eventPublisher.publishRuleChanged(tenantId, saved.getId(), "UPDATED"))
                .thenReturn(saved));
    }

    public Mono<Void> delete(UUID id, UUID tenantId) {
        return findById(id, tenantId)
                .flatMap(rule -> repository.delete(rule).thenReturn(rule.getId()))
                .flatMap(deletedId -> reloadTenantContainer(tenantId)
                        .then(eventPublisher.publishRuleChanged(tenantId, deletedId, "DELETED")));
    }

    public Mono<TenantRule> setEnabled(UUID id, UUID tenantId, boolean enabled, UUID actorId) {
        return findById(id, tenantId).flatMap(rule -> {
            rule.setEnabled(enabled);
            rule.setUpdatedAt(Instant.now());
            rule.setUpdatedBy(actorId);
            return repository.save(rule);
        }).flatMap(saved -> reloadTenantContainer(tenantId)
                .then(eventPublisher.publishRuleChanged(tenantId, saved.getId(),
                        enabled ? "ENABLED" : "DISABLED"))
                .thenReturn(saved));
    }

    // ── Dry-run ───────────────────────────────────────────────────────────────

    /**
     * Evaluate a single rule (in isolation from the rest of the tenant's set)
     * against the supplied facts. Loads ONLY the requested rule into a temporary
     * tenant key so the rest of the live KieContainer stays untouched, then
     * tears the temporary key down. The live tenant container is rebuilt
     * afterwards as a defensive measure.
     */
    public Mono<DryRunResponse> dryRun(UUID id, UUID tenantId, DryRunRequest req) {
        return findById(id, tenantId).flatMap(rule -> Mono.fromCallable(() -> {
            String tempKey = "dryrun-" + tenantId + "-" + rule.getId();
            try {
                RuleDefinition def = objectMapper.readValue(rule.getDefinition(), RuleDefinition.class);
                def.setEnabled(true); // dry-run ignores the disabled flag — that's the point
                engine.loadRules(tempKey, List.of(def));

                ClaimFact           claim        = req.claim()        == null ? new ClaimFact() : objectMapper.treeToValue(req.claim(),        ClaimFact.class);
                MemberFact          member       = req.member()       == null ? null            : objectMapper.treeToValue(req.member(),       MemberFact.class);
                DependantFact       dependant    = req.dependant()    == null ? null            : objectMapper.treeToValue(req.dependant(),    DependantFact.class);
                ProviderFact        provider     = req.provider()     == null ? null            : objectMapper.treeToValue(req.provider(),     ProviderFact.class);
                FamilyFact          family       = req.family()       == null ? null            : objectMapper.treeToValue(req.family(),       FamilyFact.class);
                MemberLifecycleFact lifecycle    = req.lifecycle()    == null ? null            : objectMapper.treeToValue(req.lifecycle(),    MemberLifecycleFact.class);
                ContributionFact    contribution = req.contribution() == null ? null            : objectMapper.treeToValue(req.contribution(), ContributionFact.class);
                PaymentRunFact      paymentRun   = req.paymentRun()   == null ? null            : objectMapper.treeToValue(req.paymentRun(),   PaymentRunFact.class);
                TimeFact            time         = req.time()         == null ? null            : objectMapper.treeToValue(req.time(),         TimeFact.class);

                long t0 = System.nanoTime();
                List<RuleResult> matched = engine.evaluate(tempKey,
                        claim, member, dependant, provider, family,
                        lifecycle, contribution, paymentRun, time);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                String decision = matched.stream()
                        .filter(r -> "REJECT".equalsIgnoreCase(r.getType()))
                        .findFirst()
                        .map(r -> "REJECTED")
                        .orElse(claim.hasRejections() ? "REJECTED" : "ACCEPTED");

                return new DryRunResponse(rule.getId(), decision, new ArrayList<>(matched), elapsedMs);
            } finally {
                engine.removeRules(tempKey);
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // ── Hot-reload helper ─────────────────────────────────────────────────────

    /**
     * Rebuild the tenant's live KieContainer from the current set of enabled
     * rules. Called after every CRUD mutation. Delegates to
     * {@link TenantRuleLoader#forceReload(UUID)} so the same code path runs
     * inside this JVM and inside every other consumer (claims-service, etc.).
     */
    public Mono<Void> reloadTenantContainer(UUID tenantId) {
        return loader.forceReload(tenantId);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private String serialize(RuleDefinition def) {
        try {
            return objectMapper.writeValueAsString(def);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize rule definition: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rule definition could not be serialized");
        }
    }

    /** Used by the controller to convert stored JSON string back into a JsonNode for the response DTO. */
    public JsonNode parseDefinition(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            log.warn("Stored rule definition is not valid JSON: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}
