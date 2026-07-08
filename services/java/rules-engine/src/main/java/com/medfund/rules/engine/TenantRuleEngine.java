package com.medfund.rules.engine;

import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.model.RuleDefinition;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-tenant Drools KieSessions.
 * <p>
 * Each tenant gets its own {@link KieContainer} built from their configured rules.
 * Containers are stored in a {@link ConcurrentHashMap} keyed by tenant ID.
 * <p>
 * This is the main entry point for rule evaluation. Consumer services should wrap calls
 * to {@link #evaluate} in {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * since Drools rule firing is a blocking operation.
 */
@Component
public class TenantRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(TenantRuleEngine.class);

    private final ConcurrentHashMap<String, KieContainer> tenantContainers = new ConcurrentHashMap<>();
    private final DrlCompiler compiler;

    public TenantRuleEngine(DrlCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Compile and load rules for a tenant. Replaces any previously loaded rules.
     * Called on tenant initialization or when rules are changed (hot-reload).
     *
     * @param tenantId the tenant identifier
     * @param rules    the list of rule definitions to compile
     * @throws RuleCompilationException if the compiled DRL contains errors
     */
    public void loadRules(String tenantId, List<RuleDefinition> rules) {
        log.info("Loading {} rules for tenant {}", rules.size(), tenantId);

        String drl = compiler.compileAll(rules);
        log.debug("Compiled DRL for tenant {}:\n{}", tenantId, drl);

        KieServices kieServices = KieServices.Factory.get();
        // Each tenant gets a unique ReleaseId so its KieModule lives at its own
        // coordinates in the shared KieRepository. Without this every tenant's
        // build would overwrite the same default release ID, and later tenants
        // would silently shadow earlier ones — fatal for multi-tenant isolation.
        // The version is bumped per call so reloadRules also gets a fresh module
        // (replacing the previous version for this tenant cleanly).
        ReleaseId releaseId = kieServices.newReleaseId(
                "com.medfund.rules.tenant",
                "tenant-" + tenantId,
                System.currentTimeMillis() + "-" + Integer.toHexString(System.identityHashCode(rules)));

        KieFileSystem kfs = kieServices.newKieFileSystem();
        kfs.generateAndWritePomXML(releaseId);
        kfs.write("src/main/resources/rules/tenant_" + tenantId + ".drl", drl);

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();
        Results results = kieBuilder.getResults();

        if (results.hasMessages(Message.Level.ERROR)) {
            String errors = results.getMessages(Message.Level.ERROR).toString();
            log.error("DRL compilation errors for tenant {}: {}", tenantId, errors);
            throw new RuleCompilationException(
                    "Failed to compile rules for tenant " + tenantId + ": " + errors);
        }

        if (results.hasMessages(Message.Level.WARNING)) {
            log.warn("DRL compilation warnings for tenant {}: {}",
                    tenantId, results.getMessages(Message.Level.WARNING));
        }

        KieContainer container = kieServices.newKieContainer(releaseId);
        // Atomic swap: any concurrent evaluate() either sees the previous
        // container (and runs to completion against it, since the KieModule
        // captured inside is immutable) or the new one — never a half-built
        // state. We deliberately do NOT dispose the previous container here:
        // an in-flight evaluate() may still be opening a KieSession on it,
        // and Drools' KieContainer GC will reclaim it once no thread holds
        // a reference.
        tenantContainers.put(tenantId, container);

        log.info("Rules loaded successfully for tenant {}", tenantId);
    }

    /**
     * Reload (replace) rules for a tenant. Semantically identical to {@link #loadRules}.
     */
    public void reloadRules(String tenantId, List<RuleDefinition> rules) {
        loadRules(tenantId, rules);
    }

    /**
     * Evaluate facts against the tenant's loaded rules.
     * <p>
     * Creates a new {@link KieSession}, inserts all provided facts, fires all rules,
     * then extracts {@link RuleResult}s from any {@link ClaimFact} in the facts array.
     * <p>
     * <b>IMPORTANT:</b> This is a blocking operation. Consumer services must wrap calls in
     * {@code Mono.fromCallable(() -> engine.evaluate(...)).subscribeOn(Schedulers.boundedElastic())}
     *
     * @param tenantId the tenant identifier
     * @param facts    the fact objects to insert (ClaimFact, MemberFact, ProviderFact, etc.)
     * @return list of rule results; empty if no rules loaded or no results produced
     */
    public List<RuleResult> evaluate(String tenantId, Object... facts) {
        return fire(tenantId, null, facts);
    }

    /**
     * Evaluate facts against the tenant's loaded rules, scoped to a single
     * agenda-gated category ({@code agendaGroup} matches the group name
     * emitted by {@code DrlCompiler} for gated categories — currently only
     * {@code BENEFIT_PRORATION}). Only rules tagged with that agenda-group
     * fire; MAIN rules stay dormant.
     *
     * <p>This is how {@code ProrationService} runs BENEFIT_PRORATION rules in
     * stage 3 without them also firing during the stage-7 tenant-rules sweep.
     * Kept as a separate method (not an overload of {@link #evaluate}) so
     * Mockito {@code any()} matchers on the existing single-arg tests remain
     * unambiguous.
     */
    public List<RuleResult> evaluateInGroup(String tenantId, String agendaGroup, Object... facts) {
        return fire(tenantId, agendaGroup, facts);
    }

    private List<RuleResult> fire(String tenantId, String agendaGroup, Object... facts) {
        KieContainer container = tenantContainers.get(tenantId);
        if (container == null) {
            log.debug("No rules loaded for tenant {} — pass-through", tenantId);
            return Collections.emptyList();
        }

        KieSession session = container.newKieSession();
        try {
            ClaimFact claimFact = null;

            for (Object fact : facts) {
                if (fact != null) {
                    session.insert(fact);
                    if (fact instanceof ClaimFact) {
                        claimFact = (ClaimFact) fact;
                    }
                }
            }

            if (agendaGroup != null) {
                session.getAgenda().getAgendaGroup(agendaGroup).setFocus();
            }

            int rulesFired = session.fireAllRules();
            log.debug("Fired {} rules for tenant {} (agendaGroup={})", rulesFired, tenantId, agendaGroup);

            if (claimFact != null) {
                return claimFact.getResults();
            }
            return Collections.emptyList();
        } finally {
            session.dispose();
        }
    }

    /**
     * Check whether the given tenant has rules loaded.
     */
    public boolean hasRulesLoaded(String tenantId) {
        return tenantContainers.containsKey(tenantId);
    }

    /**
     * Remove all loaded rules for a tenant, freeing resources.
     */
    public void removeRules(String tenantId) {
        KieContainer removed = tenantContainers.remove(tenantId);
        if (removed != null) {
            removed.dispose();
            log.info("Rules removed for tenant {}", tenantId);
        }
    }
}
