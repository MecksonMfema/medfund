package com.medfund.rules.template;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;

import java.util.List;

/**
 * Contract for contributing seeded {@link RuleDefinition} starting points
 * into the New Rule modal's template gallery.
 *
 * <p>Adding support for a new rule domain is one new class implementing this
 * interface — {@link RuleTemplateService} aggregates every {@code TemplateProvider}
 * Spring bean automatically. No edits to existing files required.
 *
 * <p>Each provider focuses on a single {@link RuleCategory} so the catalogue
 * is browseable per-domain and the Angular UI can group templates cleanly.
 */
public interface TemplateProvider {

    /** The category this provider supplies templates for. */
    RuleCategory category();

    /** Templates the provider contributes. May be empty (e.g. before seeds are written). */
    List<RuleDefinition> templates();
}
