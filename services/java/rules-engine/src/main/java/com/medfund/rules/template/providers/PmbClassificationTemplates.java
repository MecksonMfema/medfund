package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the {@code PMB_CLASSIFICATION} rule category (Phase 17
 * §B REG7). Each template ships a skeleton {@code SET_PMB_CLASSIFICATION}
 * action encoding {@code PMB_CONDITION_CODE:<code>} — see
 * {@link com.medfund.rules.compiler.SetPmbClassificationEmitter} for the
 * value grammar. Rules in this category are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) so they never fire during the
 * default tenant-rule sweep — claims-service's {@code RulesEnginePmbClassifier}
 * focuses the {@code PMB_CLASSIFICATION} group per (claim, diagnosis,
 * procedure) probe at adjudication time (and at backfill time via
 * {@code PmbBackfillJob}), reading the mutated fact after the fire.
 *
 * <p>Two starter shapes: (1) match on ICD-10 diagnosis alone — the most
 * common CMS PMB rule ("any claim with diagnosis A15.0 is PMB condition
 * PMB-001, regardless of procedure"); (2) match on both diagnosis +
 * procedure — used for the small handful of PMBs where the condition is
 * only PMB in a specific care setting (e.g. dialysis in-centre vs at-home).
 */
@Component
public class PmbClassificationTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.PMB_CLASSIFICATION;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("PMB1 - Match by ICD diagnosis code",
                 "Classify a claim as PMB when its ICD-10 diagnosis matches the "
                       + "given code. Change the diagnosis code + PMB condition code "
                       + "for your target CMS PMB entry. The condition filters on "
                       + "diagnosisCode alone so the rule fires regardless of the "
                       + "procedure - right for most PMB conditions.",
                 RuleCategory.PMB_CLASSIFICATION, 100,
                 all(cond("pmbClassification.diagnosisCode", "EQUALS", "A15.0")),
                 setPmbClassification("PMB_CONDITION_CODE:PMB-001",
                                      "PMB match by diagnosis code")),

            rule("PMB2 - Match by ICD and procedure code",
                 "Classify a claim as PMB only when BOTH the ICD-10 diagnosis and "
                       + "the tariff / procedure code match - the CMS uses this shape "
                       + "for the small set of PMBs that only qualify in a specific "
                       + "care setting (e.g. dialysis in-centre). Change both codes "
                       + "and the PMB condition code for your target entry. "
                       + "NOTE: procedure codes must be non-numeric-looking (e.g. "
                       + "prefix with a letter) - the DRL compiler emits pure-numeric "
                       + "strings as integer literals which won't match a String field.",
                 RuleCategory.PMB_CLASSIFICATION, 100,
                 all(cond("pmbClassification.diagnosisCode", "EQUALS", "N18.6"),
                     cond("pmbClassification.procedureCode", "EQUALS", "HD-CENTRE")),
                 setPmbClassification("PMB_CONDITION_CODE:PMB-070",
                                      "PMB match by diagnosis + procedure"))
        );
    }

    private static RuleAction setPmbClassification(String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("SET_PMB_CLASSIFICATION");
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
