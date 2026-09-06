package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.any;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the {@code ACTUARIAL} rule category. Each template
 * ships a skeleton {@code SELECT_LDF} action encoding an
 * {@code LDF_METHOD:<name>} value. Because these rules are agenda-gated
 * (see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) they never fire
 * during the default tenant-rule sweep — finance-service's
 * {@code TriangleShapingService} explicitly focuses the {@code ACTUARIAL}
 * agenda group before shaping an IBNR / loss triangle and passes the
 * chosen method as the job parameter to ai-service's chain-ladder compute.
 */
@Component
public class ActuarialTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.ACTUARIAL;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("A80 - Volume-weighted LDF (default IBNR)",
                 "Default loss-development-factor selection for chain-ladder IBNR and loss "
                       + "triangles. Each period's LDF is the ratio of cumulative losses summed "
                       + "across cohorts - the chainladder-python default. Priority 100 so a more "
                       + "specific rule (A81 / A82) can override for a targeted line or period.",
                 RuleCategory.ACTUARIAL, 100,
                 any(cond("triangle.insuranceLine", "EQUALS", "HEALTH"),
                     cond("triangle.insuranceLine", "EQUALS", "LIFE"),
                     cond("triangle.insuranceLine", "EQUALS", "FUNERAL"),
                     cond("triangle.insuranceLine", "EQUALS", "DISABILITY"),
                     cond("triangle.insuranceLine", "EQUALS", "VEHICLE"),
                     cond("triangle.insuranceLine", "EQUALS", "PROPERTY")),
                 selectLdf("LDF_METHOD:volume",
                           "Default volume-weighted chain-ladder")),

            rule("A81 - Simple-average LDF (low-volume lines)",
                 "Simple arithmetic average of per-cohort LDFs. Applies when volume is uneven "
                       + "across cohorts and a volume-weighted average would be dominated by a "
                       + "single outlier cohort. Default target is FUNERAL where cohort volumes "
                       + "can swing sharply month-to-month; extend the condition to any line "
                       + "with similar volume characteristics.",
                 RuleCategory.ACTUARIAL, 90,
                 all(cond("triangle.insuranceLine", "EQUALS", "FUNERAL")),
                 selectLdf("LDF_METHOD:simple",
                           "Simple average selected for low-volume line")),

            rule("A82 - Five-year weighted LDF (damp distant history)",
                 "Volume-weighted across the most recent five accident cohorts only. Damps "
                       + "distant history where a legacy claim profile no longer reflects "
                       + "current book behaviour (e.g. after a scheme re-pricing). Default target "
                       + "is HEALTH where the tariff schedule changes annually; adjust the line "
                       + "condition to match your tenant's book.",
                 RuleCategory.ACTUARIAL, 80,
                 all(cond("triangle.insuranceLine", "EQUALS", "HEALTH")),
                 selectLdf("LDF_METHOD:5yr",
                           "5-year weighted average for HEALTH"))
        );
    }

    private static RuleAction selectLdf(String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("SELECT_LDF");
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
