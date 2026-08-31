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
 * Seed templates for the {@code REGULATORY_PARAMETER} rule category. Each
 * template ships a skeleton {@code SET_REGULATORY_PARAMETER} action encoding
 * {@code PARAMETER_VALUE:<decimal>} — see
 * {@link com.medfund.rules.compiler.SetRegulatoryParameterEmitter} for the
 * value grammar. Because these rules are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) they never fire during the
 * default tenant-rule sweep — finance-service's
 * {@code RegulatoryParameterResolver} explicitly focuses the
 * {@code REGULATORY_PARAMETER} agenda group per parameter lookup at report
 * compute time and reads the chosen value back off the fact.
 *
 * <p>The three templates cover the three currently-live regulator YAML
 * bundles (IPEC, CMS, NAIC). Tenant admins customise the parameter key,
 * value, and effective-from filter; the shipped rules are agenda-gated so
 * an unmodified template does not accidentally override a live report.
 */
@Component
public class RegulatoryParameterTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.REGULATORY_PARAMETER;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("R70 - IPEC min_solvency_ratio override",
                 "Override the IPEC short-term insurance minimum solvency ratio for ZW "
                       + "tenants. Bundled YAML default is 1.30 (regulatory-defaults/"
                       + "ZW_IPEC_SHORT_TERM/2024-06-01.yaml). Adjust the value if the "
                       + "regulator publishes an updated ratio before the bundled YAML is "
                       + "refreshed. The condition filters on parameterKey +"
                       + " jurisdiction so this rule only fires for the IPEC lookup.",
                 RuleCategory.REGULATORY_PARAMETER, 100,
                 all(cond("regulatoryParameter.parameterKey", "EQUALS", "min_solvency_ratio"),
                     cond("regulatoryParameter.jurisdiction", "EQUALS", "ZW_IPEC_SHORT_TERM")),
                 setRegulatoryParameter("PARAMETER_VALUE:1.30",
                                        "IPEC min_solvency_ratio override")),

            rule("R71 - CMS non_healthcare_cost_target override",
                 "Override the CMS non-healthcare-cost target for ZA medical-scheme "
                       + "tenants. Bundled YAML default is 0.10 (regulatory-defaults/"
                       + "ZA_CMS_MEDICAL_SCHEME/2024-06-01.yaml). Adjust the value if the "
                       + "council publishes updated guidance before the bundled YAML is "
                       + "refreshed. The condition filters on parameterKey + jurisdiction "
                       + "so this rule only fires for the CMS lookup.",
                 RuleCategory.REGULATORY_PARAMETER, 100,
                 all(cond("regulatoryParameter.parameterKey", "EQUALS", "non_healthcare_cost_target"),
                     cond("regulatoryParameter.jurisdiction", "EQUALS", "ZA_CMS_MEDICAL_SCHEME")),
                 setRegulatoryParameter("PARAMETER_VALUE:0.10",
                                        "CMS non_healthcare_cost_target override")),

            rule("R72 - NAIC certified_reinsurer_provision_percentage override",
                 "Override the NAIC Schedule F statutory provision percentage on liability "
                       + "ceded to certified reinsurers for US tenants. Bundled YAML default "
                       + "is 0.20 (regulatory-defaults/US_NAIC/2024-06-01.yaml — mid-range "
                       + "placeholder pending the graduated AM Best / S&P rating table). "
                       + "Adjust the value to your tenant's per-rating credit-for-reinsurance "
                       + "table. The condition filters on parameterKey + jurisdiction so "
                       + "this rule only fires for the NAIC lookup.",
                 RuleCategory.REGULATORY_PARAMETER, 100,
                 all(cond("regulatoryParameter.parameterKey", "EQUALS",
                          "certified_reinsurer_provision_percentage"),
                     cond("regulatoryParameter.jurisdiction", "EQUALS", "US_NAIC")),
                 setRegulatoryParameter("PARAMETER_VALUE:0.20",
                                        "NAIC certified_reinsurer_provision_percentage override"))
        );
    }

    private static RuleAction setRegulatoryParameter(String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("SET_REGULATORY_PARAMETER");
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
