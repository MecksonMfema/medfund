package com.medfund.rules.service;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates every {@link TemplateProvider} bean into a single read-only catalogue.
 *
 * <p>Adding new templates is a one-class change — drop a new
 * {@code TemplateProvider} {@code @Component} into
 * {@code com.medfund.rules.template.providers}. This service picks it up
 * automatically through Spring DI; no edits here, no registry updates.
 */
@Service
public class RuleTemplateService {

    private final List<TemplateProvider> providers;

    public RuleTemplateService(List<TemplateProvider> providers) {
        this.providers = providers;
    }

    /** Every seeded template, in provider-injection order. */
    public List<RuleDefinition> getDefaultRules() {
        return providers.stream()
                .flatMap(p -> p.templates().stream())
                .toList();
    }

    /** Templates filtered to a single category. */
    public List<RuleDefinition> getDefaultRules(RuleCategory category) {
        return providers.stream()
                .filter(p -> p.category() == category)
                .flatMap(p -> p.templates().stream())
                .toList();
    }

    /** Diagnostic — number of templates each category currently has. */
    public Map<RuleCategory, Long> countsByCategory() {
        return providers.stream()
                .collect(Collectors.groupingBy(TemplateProvider::category,
                        Collectors.summingLong(p -> p.templates().size())));
    }
}
