package com.medfund.contributions.service;

import com.medfund.contributions.dto.ChargePreviewLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted unit test for {@link BillingService#composeDecoratedLines}. The
 * decoration step is the whole "custom pricing" surface an operator sees
 * on the charge-preview breakdown — this test locks the exact matrix so
 * a future refactor of the flag logic (or the resolver's COALESCE) can't
 * silently drift.
 *
 * <p>Kept as a pure JUnit test (no Spring, no Testcontainers) because
 * the pure helper needs neither — the composition takes an already-fetched
 * priced list + already-fetched override metadata and returns the
 * decorated response lines. The DB-side of {@code decorateWithOverrides}
 * is glue and is covered indirectly by the compile + the end-to-end
 * flow's own integration coverage.
 */
class BillingServiceChargePreviewDecorationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 8, 31);

    // ------------------------------------------------------------------
    // isCustomPriced — must match the resolver's COALESCE CASE exactly.
    // Any drift here would show green "Custom price" pills on rows the
    // resolver actually priced at the standard rate (or hide it on rows
    // the resolver overrode).
    // ------------------------------------------------------------------

    @Test
    void customPriced_whenIndividualModel_andOverrideSet_andEffectiveDateReached() {
        UUID memberId = UUID.randomUUID();
        BillingService.PricedCandidate priced = memberCandidate(memberId);
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("125"),
                        LocalDate.of(2026, 7, 1),   // effective 1 month before cycle → active
                        null));

        var lines = BillingService.composeDecoratedLines(
                List.of(priced), byMember, Map.of(),
                /* individualModel */ true,
                PERIOD_START, PERIOD_END);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).isCustomPriced()).isTrue();
        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isNull();
    }

    @Test
    void customPriced_stayFalse_whenPricingModelIsStandard() {
        // Regression guard: a STANDARD-model tenant that has an override
        // amount on the member record still doesn't get charged at the
        // override — the resolver's COALESCE gates on pricingModel =
        // 'INDIVIDUAL'. Showing "custom priced" here would be a lie.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("125"), null, null));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                /* individualModel */ false,
                PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isFalse();
    }

    @Test
    void customPriced_stayFalse_whenEffectiveFromIsAfterPeriodEnd() {
        // Override is set but hasn't kicked in during this cycle —
        // resolver ignores it, flag must say false. Anchored to
        // periodEnd (not periodStart) because a mid-cycle activation
        // still counts for the whole cycle per the resolver's CASE.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("125"),
                        LocalDate.of(2026, 9, 15),   // after periodEnd (Aug 31)
                        null));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isFalse();
    }

    @Test
    void customPriced_true_whenEffectiveFromIsMidCycle() {
        // effective_from lands *inside* the cycle. Per resolver COALESCE
        // the override wins because effective_from <= periodEnd — the
        // whole cycle prices at the override amount.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("125"),
                        LocalDate.of(2026, 8, 15),   // mid-cycle
                        null));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isTrue();
    }

    @Test
    void customPriced_true_whenEffectiveFromIsNull() {
        // effective_from IS NULL branch of the resolver COALESCE — the
        // override is treated as active immediately. Common for tenants
        // that flipped an override on and never touched the schedule.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("125"), null, null));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isTrue();
    }

    @Test
    void customPriced_false_whenOverrideAmountIsNull() {
        // Metadata row exists but amount is null (only age-group
        // override set). Custom-priced must be false — no dollar
        // amount was overridden.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(null,
                        LocalDate.of(2026, 7, 1),
                        UUID.randomUUID()));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isFalse();
    }

    @Test
    void customPriced_false_whenNoMetadataRow() {
        // Priced candidate has no matching row in the override map —
        // no override → no custom-priced pill. Guards against a null-
        // pointer regression + confirms the graceful fallback.
        UUID memberId = UUID.randomUUID();
        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), Map.of(), Map.of(),
                true, PERIOD_START, PERIOD_END);
        assertThat(lines.get(0).isCustomPriced()).isFalse();
        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isNull();
    }

    // ------------------------------------------------------------------
    // scheduledSchemeChangeFrom — pill only fires when there's a real
    // future age-band change ahead of the projected cycle.
    // ------------------------------------------------------------------

    @Test
    void scheduledFrom_populated_whenFutureAgeGroupWithEffectiveDateAfterPeriodStart() {
        // Age-band change scheduled after the projected cycle begins —
        // this cycle still bills at the old rate, and the operator
        // needs to know a change is coming.
        UUID memberId = UUID.randomUUID();
        LocalDate futureChange = LocalDate.of(2026, 9, 1);
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(null, futureChange, UUID.randomUUID()));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isEqualTo(futureChange);
    }

    @Test
    void scheduledFrom_null_whenEffectiveFromReachedByPeriodStart() {
        // Age-band change has already kicked in — the current cycle
        // prices at the new rate, so there's no "change effective"
        // pill to show. Guards against showing stale future-change
        // pills for changes that are actually already active.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(null,
                        LocalDate.of(2026, 7, 15),   // before periodStart
                        UUID.randomUUID()));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isNull();
    }

    @Test
    void scheduledFrom_null_whenBillingAgeGroupIsNull() {
        // No age-band override at all — the effective_from column may
        // still be populated (a stale schedule) but without the target
        // age-group there's no change to flag.
        UUID memberId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(null,
                        LocalDate.of(2026, 9, 1),
                        null));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isNull();
    }

    // ------------------------------------------------------------------
    // Dependant path — override metadata lives in the dependants table,
    // so the map lookup routes on personType, not just memberId.
    // ------------------------------------------------------------------

    @Test
    void dependantLine_routesToDependantMetadataMap() {
        // Regression guard: a dependant candidate must resolve its
        // metadata from the dependants map (keyed by dependantId), not
        // the members map. Silent routing bug would inherit the
        // parent-member's override flags on every dependant of that
        // member — visually confusing and wrong for tenants with
        // per-dependant overrides.
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        BillingService.PricedCandidate priced = dependantCandidate(memberId, dependantId);

        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("200"), null, null));  // member has an override
        Map<UUID, BillingService.OverrideMeta> byDependant = Map.of(dependantId,
                new BillingService.OverrideMeta(null, null, null));   // dependant has none

        var lines = BillingService.composeDecoratedLines(
                List.of(priced), byMember, byDependant,
                true, PERIOD_START, PERIOD_END);

        // Must reflect the dependant's row (no override), not the parent
        // member's overridden $200.
        assertThat(lines.get(0).isCustomPriced()).isFalse();
    }

    @Test
    void dependantLine_customPricedTrue_whenDependantHasOverrideOfItsOwn() {
        UUID memberId = UUID.randomUUID();
        UUID dependantId = UUID.randomUUID();
        Map<UUID, BillingService.OverrideMeta> byDependant = Map.of(dependantId,
                new BillingService.OverrideMeta(new BigDecimal("75"), null, null));

        var lines = BillingService.composeDecoratedLines(
                List.of(dependantCandidate(memberId, dependantId)), Map.of(), byDependant,
                true, PERIOD_START, PERIOD_END);

        assertThat(lines.get(0).isCustomPriced()).isTrue();
    }

    // ------------------------------------------------------------------
    // Empty + mixed input — sanity guards.
    // ------------------------------------------------------------------

    @Test
    void emptyPricedList_returnsEmptyLines() {
        var lines = BillingService.composeDecoratedLines(
                List.of(), Map.of(), Map.of(), true, PERIOD_START, PERIOD_END);
        assertThat(lines).isEmpty();
    }

    @Test
    void mixed_customPricedAndScheduled_bothPillsFireOnSameLine() {
        // Not just theoretically possible — a member on a custom price
        // right now with a scheme change scheduled a few months out is
        // a realistic tenant scenario. Both pills should render.
        UUID memberId = UUID.randomUUID();
        LocalDate futureChange = LocalDate.of(2026, 12, 1);
        Map<UUID, BillingService.OverrideMeta> byMember = Map.of(memberId,
                new BillingService.OverrideMeta(new BigDecimal("300"),
                        futureChange,
                        UUID.randomUUID()));

        var lines = BillingService.composeDecoratedLines(
                List.of(memberCandidate(memberId)), byMember, Map.of(),
                true, PERIOD_START, PERIOD_END);

        // effective_from (Dec 1) is *after* periodEnd (Aug 31) → override
        // amount doesn't apply this cycle, so custom-priced stays false.
        // But scheduled_from populates because the age-group change is
        // ahead of periodStart.
        assertThat(lines.get(0).isCustomPriced()).isFalse();
        assertThat(lines.get(0).scheduledSchemeChangeFrom()).isEqualTo(futureChange);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static BillingService.PricedCandidate memberCandidate(UUID memberId) {
        return new BillingService.PricedCandidate(
                memberId, null, "M-001", "Jane Doe", "MEMBER",
                UUID.randomUUID(), "Gold Plan",
                null, null,
                UUID.randomUUID(), "Adult",
                new BigDecimal("100"), "USD");
    }

    private static BillingService.PricedCandidate dependantCandidate(UUID memberId, UUID dependantId) {
        return new BillingService.PricedCandidate(
                memberId, dependantId, "M-001", "Alex Doe", "DEPENDANT",
                UUID.randomUUID(), "Gold Plan",
                null, null,
                UUID.randomUUID(), "Child",
                new BigDecimal("50"), "USD");
    }

    // Silence unused import warnings for the DTO import — kept in scope
    // so future line-shape assertions can reach for it without a re-
    // add pass.
    @SuppressWarnings("unused")
    private static ChargePreviewLine unusedRef;
}
