package com.medfund.rules.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in category-catalog stability. Enum values are string keys stored
 * in tenant rows — deleting or renaming one silently breaks existing rules.
 * If a value is added the test must be updated in the same commit.
 */
class RuleCategoryTest {

    @Test
    void reinsuranceCategory_isDeclared() {
        // Phase 10 addition — used by CEDE_TO_TREATY templates + rules.
        assertThat(RuleCategory.valueOf("REINSURANCE"))
                .isEqualTo(RuleCategory.REINSURANCE);
    }

    @Test
    void enumHasExpectedCatalogSize() {
        // Guardrail: if this fires, a new category was added — update the
        // Angular RULE_CATEGORIES + permissions catalog + tenant rules docs.
        assertThat(RuleCategory.values()).hasSize(17);
    }
}
