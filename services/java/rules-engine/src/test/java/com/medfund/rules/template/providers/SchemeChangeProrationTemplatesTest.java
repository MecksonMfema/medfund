package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeChangeProrationTemplatesTest {

    private final SchemeChangeProrationTemplates provider = new SchemeChangeProrationTemplates();

    @Test
    void category_isBenefitProration() {
        assertThat(provider.category()).isEqualTo(RuleCategory.BENEFIT_PRORATION);
    }

    @Test
    void templates_shipsThreeStartingPoints() {
        List<RuleDefinition> templates = provider.templates();
        assertThat(templates).hasSize(3);
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("BENEFIT_PRORATION");
            assertThat(t.getAction().getType()).isEqualTo("APPLY_PRORATION_STRATEGY");
            assertThat(t.getAction().getValue()).isNotNull();
        });
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new ActionEmitters.ApplyProrationStrategyEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep", template.getName())
                    .contains("agenda-group \"BENEFIT_PRORATION\"");
            assertThat(drl).contains("$claim.setProrationStrategy(");
        }
    }
}
