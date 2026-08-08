package com.medfund.claims.service;

import com.medfund.claims.entity.ClaimLine;
import com.medfund.rules.fact.ClaimDetailFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit coverage for the modifier-related helpers on {@link ClaimFactBuilder}.
 * The reactive {@code build(Claim)} path is exercised end-to-end by
 * {@link AdjudicationPipelineTest} — this class pins the deterministic shape
 * of the {@link ClaimDetailFact}s produced from a {@link ClaimLine} list so
 * the pipeline can trust the mapping.
 */
class ClaimFactBuilderModifierTest {

    @Test
    void toDetailFacts_preservesOrderAndAssignsOneBasedRank() {
        ClaimLine a = line("TC001", "10.00", null);
        ClaimLine b = line("TC002", "20.00", "BIL");
        ClaimLine c = line("TC003", "30.00", "AS,MOD-EMR");

        List<ClaimDetailFact> facts = ClaimFactBuilder.toDetailFacts(List.of(a, b, c));

        assertThat(facts).hasSize(3);
        assertThat(facts.get(0).getTariffCode()).isEqualTo("TC001");
        assertThat(facts.get(0).getProcedureRank()).isEqualTo(1);
        assertThat(facts.get(1).getProcedureRank()).isEqualTo(2);
        assertThat(facts.get(2).getProcedureRank()).isEqualTo(3);
    }

    @Test
    void toDetailFacts_seedsApprovedAmountToBilledAmount() {
        // Rule-less pass-through: with no MODIFIER_ADJUSTMENT rule firing,
        // approvedAmount stays at billedAmount so summed totals match the
        // pre-modifier claimedAmount.
        ClaimLine line = line("TC001", "12.34", null);

        ClaimDetailFact fact = ClaimFactBuilder.toDetailFacts(List.of(line)).get(0);

        assertThat(fact.getBilledAmount()).isEqualByComparingTo("12.34");
        assertThat(fact.getApprovedAmount()).isEqualByComparingTo("12.34");
    }

    @Test
    void toDetailFacts_parsesCsvModifiers_trimsAndDropsBlanks() {
        ClaimLine line = line("TC001", "10.00", " BIL , ,AS , MOD-EMR");

        List<String> modifiers = ClaimFactBuilder.toDetailFacts(List.of(line))
                .get(0).getModifiers();

        assertThat(modifiers).containsExactly("BIL", "AS", "MOD-EMR");
    }

    @Test
    void toDetailFacts_nullOrBlankModifierCodes_yieldsEmptyList() {
        ClaimLine nullMods = line("TC001", "10.00", null);
        ClaimLine blankMods = line("TC002", "10.00", "   ");

        assertThat(ClaimFactBuilder.toDetailFacts(List.of(nullMods))
                .get(0).getModifiers()).isEmpty();
        assertThat(ClaimFactBuilder.toDetailFacts(List.of(blankMods))
                .get(0).getModifiers()).isEmpty();
    }

    @Test
    void toDetailFacts_emptyOrNullInput_returnsEmptyList() {
        assertThat(ClaimFactBuilder.toDetailFacts(List.of())).isEmpty();
        assertThat(ClaimFactBuilder.toDetailFacts(null)).isEmpty();
    }

    @Test
    void parseModifiers_handlesEdgeCases() {
        assertThat(ClaimFactBuilder.parseModifiers(null)).isEmpty();
        assertThat(ClaimFactBuilder.parseModifiers("")).isEmpty();
        assertThat(ClaimFactBuilder.parseModifiers("   ")).isEmpty();
        assertThat(ClaimFactBuilder.parseModifiers("SOLO")).containsExactly("SOLO");
        assertThat(ClaimFactBuilder.parseModifiers("A,B,C")).containsExactly("A", "B", "C");
        assertThat(ClaimFactBuilder.parseModifiers("A, ,B,,C")).containsExactly("A", "B", "C");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ClaimLine line(String tariffCode, String claimedAmount, String modifierCodes) {
        ClaimLine line = new ClaimLine();
        line.setId(UUID.randomUUID());
        line.setTariffCode(tariffCode);
        line.setClaimedAmount(new BigDecimal(claimedAmount));
        line.setModifierCodes(modifierCodes);
        return line;
    }
}
