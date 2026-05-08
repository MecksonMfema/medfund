package com.medfund.rules.service;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import com.medfund.rules.template.providers.AgeGroupTemplates;
import com.medfund.rules.template.providers.BenefitLimitTemplates;
import com.medfund.rules.template.providers.ClinicalValidationTemplates;
import com.medfund.rules.template.providers.CoPaymentTemplates;
import com.medfund.rules.template.providers.ContributionBillingTemplates;
import com.medfund.rules.template.providers.ContributionPricingTemplates;
import com.medfund.rules.template.providers.EligibilityTemplates;
import com.medfund.rules.template.providers.MemberLifecycleTemplates;
import com.medfund.rules.template.providers.PreAuthorizationTemplates;
import com.medfund.rules.template.providers.ProviderPaymentTemplates;
import com.medfund.rules.template.providers.ReconciliationTemplates;
import com.medfund.rules.template.providers.TariffPricingTemplates;
import com.medfund.rules.template.providers.UnderwritingTemplates;
import com.medfund.rules.template.providers.WaitingPeriodTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test that wires every TemplateProvider implementation by hand. Adding
 * a new provider class only requires adding it to the list below — the
 * service itself doesn't need touching.
 */
class RuleTemplateServiceTest {

    private RuleTemplateService service;

    @BeforeEach
    void setUp() {
        List<TemplateProvider> providers = List.of(
                new EligibilityTemplates(),
                new WaitingPeriodTemplates(),
                new BenefitLimitTemplates(),
                new PreAuthorizationTemplates(),
                new TariffPricingTemplates(),
                new ClinicalValidationTemplates(),
                new AgeGroupTemplates(),
                new MemberLifecycleTemplates(),
                new UnderwritingTemplates(),
                new ContributionPricingTemplates(),
                new ContributionBillingTemplates(),
                new CoPaymentTemplates(),
                new ProviderPaymentTemplates(),
                new ReconciliationTemplates()
        );
        service = new RuleTemplateService(providers);
    }

    @Test
    void getDefaultRules_returnsNonEmpty() {
        assertThat(service.getDefaultRules()).isNotEmpty();
    }

    @Test
    void getDefaultRules_coversEveryDeclaredCategory() {
        Set<String> categories = service.getDefaultRules().stream()
                .map(RuleDefinition::getCategory)
                .collect(Collectors.toSet());

        Set<String> expected = java.util.Arrays.stream(RuleCategory.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(categories).containsAll(expected);
    }

    @Test
    void getDefaultRules_allHaveValidStructure() {
        assertThat(service.getDefaultRules()).allSatisfy(rule -> {
            assertThat(rule.getName()).isNotNull().isNotBlank();
            assertThat(rule.getCategory()).isNotNull().isNotBlank();
            assertThat(rule.getConditions()).isNotNull();
            assertThat(rule.getAction()).isNotNull();
        });
    }

    @Test
    void getDefaultRules_filteredByCategory_returnsOnlyThatCategory() {
        List<RuleDefinition> contribs = service.getDefaultRules(RuleCategory.CONTRIBUTION_PRICING);

        assertThat(contribs).isNotEmpty();
        assertThat(contribs).allMatch(r -> RuleCategory.CONTRIBUTION_PRICING.name().equals(r.getCategory()));
    }
}
