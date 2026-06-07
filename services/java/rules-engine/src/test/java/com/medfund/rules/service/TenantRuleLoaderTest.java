package com.medfund.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.entity.TenantRule;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.repository.TenantRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantRuleLoader}. The loader is the lazy bridge
 * between the {@code tenant_rules} table and the in-process Drools engine —
 * regression here would either skip rule evaluation entirely (caching bug)
 * or recompile on every request (perf regression).
 */
@ExtendWith(MockitoExtension.class)
class TenantRuleLoaderTest {

    @Mock private TenantRuleRepository repository;
    @Mock private TenantRuleEngine     engine;

    private TenantRuleLoader loader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loader = new TenantRuleLoader(repository, engine, objectMapper);
    }

    @Test
    void ensureLoaded_skipsWhenTenantIdNull() {
        StepVerifier.create(loader.ensureLoaded(null)).verifyComplete();
        verifyNoInteractions(repository, engine);
    }

    @Test
    void ensureLoaded_skipsWhenAlreadyCached() {
        UUID tenantId = UUID.randomUUID();
        when(engine.hasRulesLoaded(tenantId.toString())).thenReturn(true);

        StepVerifier.create(loader.ensureLoaded(tenantId)).verifyComplete();

        verify(engine).hasRulesLoaded(tenantId.toString());
        verifyNoInteractions(repository);
        verify(engine, never()).reloadRules(anyString(), any());
    }

    @Test
    void ensureLoaded_compilesRulesOnFirstCall() {
        UUID tenantId = UUID.randomUUID();
        when(engine.hasRulesLoaded(tenantId.toString())).thenReturn(false);
        TenantRule rule = ruleFixture(tenantId, "Pre-existing condition", "claims", 80, true, 3);
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.just(rule));

        StepVerifier.create(loader.ensureLoaded(tenantId)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuleDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
        verify(engine).reloadRules(eq(tenantId.toString()), defsCaptor.capture());

        List<RuleDefinition> defs = defsCaptor.getValue();
        assertThat(defs).hasSize(1);
        RuleDefinition def = defs.getFirst();
        assertThat(def.getId()).isEqualTo(rule.getId().toString());
        assertThat(def.getName()).isEqualTo("Pre-existing condition");
        assertThat(def.getCategory()).isEqualTo("claims");
        assertThat(def.getPriority()).isEqualTo(80);
        assertThat(def.isEnabled()).isTrue();
        assertThat(def.getVersion()).isEqualTo(3);
    }

    @Test
    void ensureLoaded_fillsDefaultsWhenPriorityAndVersionMissing() {
        UUID tenantId = UUID.randomUUID();
        when(engine.hasRulesLoaded(tenantId.toString())).thenReturn(false);
        TenantRule rule = ruleFixture(tenantId, "No priority", "claims", null, true, null);
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.just(rule));

        StepVerifier.create(loader.ensureLoaded(tenantId)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuleDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
        verify(engine).reloadRules(eq(tenantId.toString()), defsCaptor.capture());

        RuleDefinition def = defsCaptor.getValue().getFirst();
        assertThat(def.getPriority()).isEqualTo(50); // documented default
        assertThat(def.getVersion()).isEqualTo(1);
    }

    @Test
    void ensureLoaded_loadsEmptyListWhenTenantHasNoEnabledRules() {
        UUID tenantId = UUID.randomUUID();
        when(engine.hasRulesLoaded(tenantId.toString())).thenReturn(false);
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.empty());

        StepVerifier.create(loader.ensureLoaded(tenantId)).verifyComplete();

        // The engine still receives a reloadRules call so any previously-cached
        // (now-disabled) rules are wiped from the local KieBase.
        verify(engine).reloadRules(eq(tenantId.toString()), eq(List.of()));
    }

    @Test
    void ensureLoaded_throwsIllegalStateOnCorruptDefinitionJson() {
        UUID tenantId = UUID.randomUUID();
        when(engine.hasRulesLoaded(tenantId.toString())).thenReturn(false);
        TenantRule corrupt = new TenantRule();
        corrupt.setId(UUID.randomUUID());
        corrupt.setTenantId(tenantId);
        corrupt.setName("Corrupt");
        corrupt.setCategory("claims");
        corrupt.setEnabled(true);
        corrupt.setPriority(50);
        corrupt.setVersion(1);
        corrupt.setDefinition("{not valid json");
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.just(corrupt));

        StepVerifier.create(loader.ensureLoaded(tenantId))
            .expectErrorSatisfies(err -> {
                assertThat(err).isInstanceOf(IllegalStateException.class);
                assertThat(err.getMessage()).contains(corrupt.getId().toString());
            })
            .verify();

        verify(engine, never()).reloadRules(anyString(), any());
    }

    @Test
    void invalidate_delegatesToEngineRemove() {
        UUID tenantId = UUID.randomUUID();
        loader.invalidate(tenantId);
        verify(engine).removeRules(tenantId.toString());
    }

    @Test
    void invalidate_isNoOpForNullTenant() {
        loader.invalidate(null);
        verifyNoInteractions(engine);
    }

    @Test
    void forceReload_bypassesHasRulesLoadedCheck() {
        UUID tenantId = UUID.randomUUID();
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.empty());

        StepVerifier.create(loader.forceReload(tenantId)).verifyComplete();

        // forceReload must NOT consult the cache flag — it's used after
        // invalidate() to guarantee a rebuild from the table.
        verify(engine, never()).hasRulesLoaded(anyString());
        verify(engine).reloadRules(eq(tenantId.toString()), any());
    }

    @Test
    void forceReload_canBeCalledTwiceWithoutReusingState() {
        UUID tenantId = UUID.randomUUID();
        TenantRule rule = ruleFixture(tenantId, "R", "claims", 50, true, 1);
        when(repository.findEnabledByTenant(tenantId)).thenReturn(Flux.just(rule));

        StepVerifier.create(loader.forceReload(tenantId)).verifyComplete();
        StepVerifier.create(loader.forceReload(tenantId)).verifyComplete();

        verify(engine, times(2)).reloadRules(eq(tenantId.toString()), any());
    }

    private TenantRule ruleFixture(UUID tenantId, String name, String category,
                                    Integer priority, boolean enabled, Integer version) {
        TenantRule r = new TenantRule();
        r.setId(UUID.randomUUID());
        r.setTenantId(tenantId);
        r.setName(name);
        r.setCategory(category);
        r.setEnabled(enabled);
        r.setPriority(priority);
        r.setVersion(version);
        // Minimal valid RuleDefinition JSON — keys ignored by Jackson if not on
        // the target type; the loader overrides id/name/category/priority/version/enabled.
        r.setDefinition("{\"name\":\"placeholder\"}");
        return r;
    }
}
