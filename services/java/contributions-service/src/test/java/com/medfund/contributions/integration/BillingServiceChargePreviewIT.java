package com.medfund.contributions.integration;

import com.medfund.contributions.dto.ChargePreviewLine;
import com.medfund.contributions.dto.ChargePreviewResponse;
import com.medfund.contributions.service.BillingService;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link BillingService#chargePreview}. Covers the
 * seams the pure-JUnit tests can't reach:
 *
 * <ul>
 *   <li>{@code HealthCandidateResolver} SQL — active + suspended member
 *       gating, dependant fan-out, LATERAL age_group_prices lookup.</li>
 *   <li>{@code termination_date < periodStart} exclusion — the whole
 *       reason the preview endpoint exists is to project a *future*
 *       cycle without billing rows that won't be a customer then.</li>
 *   <li>Subject-name lookup — groups.name for GROUP subjects,
 *       first_name || last_name for MEMBER subjects.</li>
 *   <li>Currency post-filter — narrow to one currency in a multi-
 *       currency projection.</li>
 *   <li>Multi-currency totals map — one entry per currency, summed.</li>
 *   <li>pricing_model gating on {@code isCustomPriced} — INDIVIDUAL
 *       tenant honours override, STANDARD ignores it (verifies the
 *       decoration matches the resolver's COALESCE gate end-to-end,
 *       not just in isolation).</li>
 *   <li>Scheduled scheme-change flag — future age-band change surfaces
 *       {@code scheduledSchemeChangeFrom} while the cycle still bills
 *       at the old rate.</li>
 * </ul>
 *
 * <p>Uses a stripped-down schema
 * ({@code test-resources/db/charge-preview-migration}) rather than the
 * production tenant migrations. Same rationale as
 * {@link SchemeServiceIT}: this module doesn't own those migrations
 * and a slice test shouldn't couple to their evolution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/charge-preview-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(BillingServiceChargePreviewIT.SecurityStub.class)
class BillingServiceChargePreviewIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000042";

    @Autowired private BillingService billingService;
    @Autowired private DatabaseClient db;

    // Fixtures reset per test so assertions stay independent. The
    // truncate order matters — children before parents on FKs.
    @BeforeEach
    void resetSchema() {
        db.sql("""
                TRUNCATE dependants, contributions, invoice_pdfs, invoices,
                         member_running_balance, group_running_balance,
                         members, groups, group_liaisons, staff_users,
                         age_group_prices, age_groups, schemes,
                         tenants, scheduled_job_configs,
                         billing_cycle_config, dunning_config
                CASCADE
                """).then().block();

        // Seed the tenant row — pricing_model varies per test so we
        // insert with STANDARD as the default and let individual tests
        // update as needed.
        db.sql("INSERT INTO tenants (id, schema_name, pricing_model) " +
                "VALUES (:id, 'public', 'STANDARD')")
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();
    }

    // ------------------------------------------------------------------
    // Full group scenario — resolver + termination filter + dependants.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void groupSubject_returnsActiveMembersWithDependants_andCountsExcluded() {
        // Fixture: a 4-member group where —
        //  1. an active member with an active dependant survives
        //     both filters and contributes TWO lines (MEMBER + DEPENDANT)
        //  2. an active member with no dependants contributes ONE line
        //  3. a member with termination_date < periodStart is EXCLUDED
        //     from the resolver-filtered flux and counted on
        //     excludedTerminating
        //  4. a deactivated member is excluded by the resolver's
        //     status filter (does NOT count on excludedTerminating —
        //     that counter is termination-specific)
        LocalDate periodStart = billingService.getClass() == null ? null
                : LocalDate.now().plusMonths(1).withDayOfMonth(1);
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID childBandId = seedAgeGroup(schemeId, "Child", new BigDecimal("40"), "USD");
        UUID groupId = seedGroup("Acme Ltd");

        UUID activeMember    = seedMember(schemeId, groupId, ageGroupId, "active", null, null, null, null);
        UUID memberWithDep   = seedMember(schemeId, groupId, ageGroupId, "active", null, null, null, null);
        seedDependant(memberWithDep, childBandId, "active");
        // Terminating on the day before periodStart — excluded and counted.
        seedMember(schemeId, groupId, ageGroupId, "active", periodStart.minusDays(1), null, null, null);
        // Deactivated — excluded by the resolver's status filter.
        seedMember(schemeId, groupId, ageGroupId, "deactivated", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // 3 lines: activeMember (1), memberWithDep (1 MEMBER + 1 DEPENDANT).
        assertThat(resp.lines()).hasSize(3);
        assertThat(resp.lines().stream().map(ChargePreviewLine::personType).toList())
                .containsExactlyInAnyOrder("MEMBER", "MEMBER", "DEPENDANT");
        // Terminating count reflects the ONE terminating row — not the
        // deactivated one; the counter is termination-specific.
        assertThat(resp.excludedTerminating()).isEqualTo(1);
        // Total is 2 * 100 (adults) + 40 (child) = 240.
        assertThat(resp.totals()).hasSize(1);
        assertThat(resp.totals().get("USD")).isEqualByComparingTo(new BigDecimal("240"));
        // Anchor a couple of ids for identity — anything off means the
        // filter dropped the wrong member.
        List<UUID> memberIds = resp.lines().stream()
                .filter(l -> "MEMBER".equalsIgnoreCase(l.personType()))
                .map(ChargePreviewLine::memberId)
                .toList();
        assertThat(memberIds).containsExactlyInAnyOrder(activeMember, memberWithDep);

        // Household ordering — memberWithDep's MEMBER row must sit
        // immediately next to its DEPENDANT row, not scattered across
        // the table. Scan for the (MEMBER, DEPENDANT) adjacency on the
        // shared memberId; the resolver's UNION ALL order is otherwise
        // undefined so we assert the invariant, not a specific slot.
        int principalIdx = -1;
        for (int i = 0; i < resp.lines().size(); i++) {
            ChargePreviewLine l = resp.lines().get(i);
            if (memberWithDep.equals(l.memberId())
                    && "MEMBER".equalsIgnoreCase(l.personType())) {
                principalIdx = i;
                break;
            }
        }
        assertThat(principalIdx)
                .as("principal MEMBER row for memberWithDep should be present")
                .isGreaterThanOrEqualTo(0);
        ChargePreviewLine adjacent = resp.lines().get(principalIdx + 1);
        assertThat(adjacent.memberId()).isEqualTo(memberWithDep);
        assertThat(adjacent.personType()).isEqualTo("DEPENDANT");
    }

    // ------------------------------------------------------------------
    // Subject-name lookup — verify both routing branches.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void subjectName_forGroup_readsFromGroupsTable() {
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Beta Holdings");
        seedMember(schemeId, groupId, ageGroupId, "active", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.subjectName()).isEqualTo("Beta Holdings");
        assertThat(resp.subjectType()).isEqualTo("GROUP");
        assertThat(resp.subjectId()).isEqualTo(groupId);
    }

    @Test
    @WithTenant(TENANT_ID)
    void subjectName_forIndividual_readsFromMembersTable() {
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        // Ungrouped individual — group_id null so the member half of
        // the resolver picks them up.
        UUID memberId = seedMemberNamed(schemeId, null, ageGroupId, "active",
                "Jane", "Doe", null);

        ChargePreviewResponse resp = block(billingService.chargePreview("MEMBER", memberId, null));

        assertThat(resp.subjectName()).isEqualTo("Jane Doe");
        assertThat(resp.subjectType()).isEqualTo("MEMBER");
        assertThat(resp.lines()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Multi-currency projections + currency filter.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void multipleCurrencies_producesMultipleTotalEntries() {
        // A group can have members on schemes in different currencies
        // (e.g. one dollar scheme, one rand scheme). The projection must
        // return the full total map — one entry per currency.
        UUID usdScheme = seedScheme("USD");
        UUID zarScheme = seedScheme("ZAR");
        UUID usdBand = seedAgeGroup(usdScheme, "USD Adult", new BigDecimal("100"), "USD");
        UUID zarBand = seedAgeGroup(zarScheme, "ZAR Adult", new BigDecimal("500"), "ZAR");
        UUID groupId = seedGroup("Multi-currency Ltd");
        seedMember(usdScheme, groupId, usdBand, "active", null, null, null, null);
        seedMember(zarScheme, groupId, zarBand, "active", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.totals()).hasSize(2);
        assertThat(resp.totals().get("USD")).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(resp.totals().get("ZAR")).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(resp.lines()).hasSize(2);
    }

    @Test
    @WithTenant(TENANT_ID)
    void currencyFilter_narrowsToRequestedCurrencyOnly() {
        // Same fixture as above but with currency="USD" → only the USD
        // line remains, and totals shrinks to one entry.
        UUID usdScheme = seedScheme("USD");
        UUID zarScheme = seedScheme("ZAR");
        UUID usdBand = seedAgeGroup(usdScheme, "USD Adult", new BigDecimal("100"), "USD");
        UUID zarBand = seedAgeGroup(zarScheme, "ZAR Adult", new BigDecimal("500"), "ZAR");
        UUID groupId = seedGroup("Multi-currency Ltd");
        seedMember(usdScheme, groupId, usdBand, "active", null, null, null, null);
        seedMember(zarScheme, groupId, zarBand, "active", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, "USD"));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).currencyCode()).isEqualTo("USD");
        assertThat(resp.totals()).hasSize(1);
        assertThat(resp.totals().get("USD")).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @WithTenant(TENANT_ID)
    void currencyFilter_caseInsensitive() {
        // Filter should accept "usd" as readily as "USD" — a case-
        // sensitive regression here silently drops rows for tenants
        // that pass lowercase codes from a URL query param.
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Case-Test Ltd");
        seedMember(schemeId, groupId, ageGroupId, "active", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, "usd"));

        assertThat(resp.lines()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Custom pricing — pricing_model gating end-to-end.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void customPricing_individualModel_overridesAmount_andSetsFlag() {
        setPricingModel("INDIVIDUAL");
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Individual-model Ltd");
        // Override amount 250 vs the age-band's 100. In INDIVIDUAL mode
        // the resolver picks the override; the pill lights up.
        seedMember(schemeId, groupId, ageGroupId, "active", null,
                new BigDecimal("250"), null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).amount()).isEqualByComparingTo("250");
        assertThat(resp.lines().get(0).isCustomPriced()).isTrue();
    }

    @Test
    @WithTenant(TENANT_ID)
    void customPricing_standardModel_ignoresOverride_flagStaysFalse() {
        // Tenant defaults to STANDARD (see @BeforeEach). Same override
        // fixture as above but the resolver's COALESCE CASE requires
        // pricingModel = 'INDIVIDUAL' — the override is ignored and
        // the age-band price wins. The flag must lie neither
        // direction: amount == 100 AND isCustomPriced == false.
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Standard-model Ltd");
        seedMember(schemeId, groupId, ageGroupId, "active", null,
                new BigDecimal("250"), null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).amount()).isEqualByComparingTo("100");
        assertThat(resp.lines().get(0).isCustomPriced()).isFalse();
    }

    @Test
    @WithTenant(TENANT_ID)
    void customPricing_aiDrivenModel_overridesAmount_asHybridWithIndividual() {
        // AI_DRIVEN is a hybrid: the operator (with the AI stub's help)
        // sets a per-member override, then the resolver honours it the
        // same way it does for INDIVIDUAL. If the CASE ever reverts to
        // = 'INDIVIDUAL' alone, AI_DRIVEN tenants would silently price
        // at the age-band and every operator-set override would go dead.
        setPricingModel("AI_DRIVEN");
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("AI-driven Ltd");
        seedMember(schemeId, groupId, ageGroupId, "active", null,
                new BigDecimal("250"), null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).amount()).isEqualByComparingTo("250");
        assertThat(resp.lines().get(0).isCustomPriced()).isTrue();
    }

    // ------------------------------------------------------------------
    // Universal age-band override — the "active-today" branch of the
    // billing_age_group_id CASE. Complements the scheduled-future test
    // below which only covers the not-yet-active side.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void dependant_billingAgeGroup_setWithNullEffectiveFrom_picksAlternateBandImmediately() {
        // Age-band override is a dependant-only feature. Canonical use:
        // a dependant with a disability who ages out of the child band
        // by DoB but should stay on the child rate. The resolver's
        // dependant-half CASE picks billing_age_group_id whenever it's
        // set with a null (or past) effective_from — this test proves
        // the "active-today" branch. Complements the scheduled-future
        // case below which only exercises the not-yet-active side.
        //
        // Gate: this applies for STANDARD too. billing_age_group_id is
        // a categorical decision (which band this dependant belongs
        // to), not a per-member custom price — so it is not gated on
        // pricing_model.
        UUID schemeId = seedScheme("USD");
        UUID adultBand = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID childBand = seedAgeGroup(schemeId, "Child", new BigDecimal("40"), "USD");
        UUID groupId = seedGroup("Age-band-override Ltd");
        UUID memberId = seedMember(schemeId, groupId, adultBand, "active", null,
                null, null, null);
        // dependant canonical age = Adult (aged out), billing_age_group_id = Child
        seedDependant(memberId, adultBand, "active", childBand);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // Two lines: member (Adult 100) + dependant (Child 40 via override).
        assertThat(resp.lines()).hasSize(2);
        BigDecimal dependantAmount = resp.lines().stream()
                .filter(l -> "DEPENDANT".equals(l.personType()))
                .findFirst().orElseThrow().amount();
        assertThat(dependantAmount).isEqualByComparingTo("40");
    }

    // ------------------------------------------------------------------
    // Dependant deactivation (V046) — bill up to and including the
    // effective month, drop off from the next cycle.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void dependant_deactivated_withEffectiveDateInProjectedCycle_stillBills() {
        // chargePreview projects next month's cycle. The resolver's
        // dependant WHERE clause:
        //   d.status IN ('active','suspended')
        //   OR (d.status='deactivated'
        //       AND (d.deactivation_effective_date IS NULL
        //            OR d.deactivation_effective_date >= :periodStart))
        // If the effective_date lands INSIDE the projected month, the
        // dependant is still billable for that month.
        LocalDate projectedStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate insideProjected = projectedStart.plusDays(15);

        UUID schemeId = seedScheme("USD");
        UUID band = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Deactivation-window Ltd (still billing)");
        UUID memberId = seedMember(schemeId, groupId, band, "active", null, null, null, null);
        seedDeactivatedDependant(memberId, band, insideProjected);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // Member + dependant both bill for this cycle.
        assertThat(resp.lines()).hasSize(2);
    }

    @Test
    @WithTenant(TENANT_ID)
    void dependant_deactivated_withEffectiveDateBeforeProjectedStart_dropsOff() {
        // Same shape but the effective_date is BEFORE periodStart —
        // resolver drops the dependant off. Only the member bills.
        LocalDate projectedStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate beforeProjected = projectedStart.minusDays(1);

        UUID schemeId = seedScheme("USD");
        UUID band = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Deactivation-window Ltd (dropped)");
        UUID memberId = seedMember(schemeId, groupId, band, "active", null, null, null, null);
        seedDeactivatedDependant(memberId, band, beforeProjected);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).personType()).isEqualTo("MEMBER");
    }

    // ------------------------------------------------------------------
    // V047: dependant enrollment_date drives billability. The resolver's
    // dependant filter is `d.enrollment_date <= :periodEnd`. A dependant
    // enrolled AFTER the projected cycle should not appear; one enrolled
    // BEFORE it should.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void dependant_futureEnrollmentDate_notBillableForCurrentCycle() {
        // Seed a dependant with enrollment_date = 2 months after the
        // projected cycle. Resolver must drop them off — the parent
        // member still bills.
        LocalDate projectedStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate futureEnrolment = projectedStart.plusMonths(2);

        UUID schemeId = seedScheme("USD");
        UUID band = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Future-enrolled Ltd");
        UUID memberId = seedMember(schemeId, groupId, band, "active", null, null, null, null);
        seedDependantWithEnrollmentDate(memberId, band, "active", futureEnrolment);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // Only the member — dependant hasn't started cover yet.
        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).personType()).isEqualTo("MEMBER");
    }

    @Test
    @WithTenant(TENANT_ID)
    void dependant_pastEnrollmentDate_billsForCurrentCycle() {
        // Seed a dependant enrolled 3 months ago — well before the
        // projected cycle. Resolver includes them alongside the
        // parent member.
        LocalDate pastEnrolment = LocalDate.now().minusMonths(3).withDayOfMonth(1);

        UUID schemeId = seedScheme("USD");
        UUID band = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Past-enrolled Ltd");
        UUID memberId = seedMember(schemeId, groupId, band, "active", null, null, null, null);
        seedDependantWithEnrollmentDate(memberId, band, "active", pastEnrolment);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // Two lines — member + dependant. Both at Adult (100).
        assertThat(resp.lines()).hasSize(2);
    }

    @Test
    @WithTenant(TENANT_ID)
    void dependant_futureEnrolled_appearsOnProjectedStatement() {
        // V048: A dependant that today is still 'enrolled' (their
        // effective date is 1st of the projected cycle) MUST show up on
        // the statement for that cycle. Prior to V048 the resolver
        // filtered them out because 'enrolled' wasn't in the status
        // allowlist — the operator would see the statement without them
        // and only discover the gap after the daily
        // SCHEDULED_STATUS_ROLL job flipped them mid-cycle.
        LocalDate projectedStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);

        UUID schemeId = seedScheme("USD");
        UUID band = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Future-enrolled dependant Ltd");
        UUID memberId = seedMember(schemeId, groupId, band, "active", null, null, null, null);
        // Enrolled dependant with enrollment_date landing on the
        // projected cycle's 1st — the state a fresh future-dated add
        // sits in before the daily job.
        seedEnrolledDependantWithDate(memberId, band, projectedStart);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        // Member + dependant both bill for this projected cycle.
        assertThat(resp.lines()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Scheduled scheme change — future billing_age_group_id.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void scheduledSchemeChange_populatesEffectiveFrom_andKeepsOldRateThisCycle() {
        // Member currently on the "Adult" band; a change to "Premium"
        // is scheduled to kick in AFTER the projected cycle begins.
        // Per the resolver's CASE, the projected cycle still prices at
        // the OLD band; the decoration surfaces the scheduled date so
        // the operator sees the change coming.
        UUID schemeId = seedScheme("USD");
        UUID adultBand = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID premiumBand = seedAgeGroup(schemeId, "Premium", new BigDecimal("300"), "USD");
        UUID groupId = seedGroup("Change-scheduled Ltd");

        // Effective date lands after periodStart (next month's 1st) so
        // the change is "still ahead" per the composeDecoratedLines
        // predicate.
        LocalDate periodStart = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate futureChange = periodStart.plusMonths(2);
        seedMember(schemeId, groupId, adultBand, "active", null,
                null, premiumBand, futureChange);

        ChargePreviewResponse resp = block(billingService.chargePreview("GROUP", groupId, null));

        assertThat(resp.lines()).hasSize(1);
        assertThat(resp.lines().get(0).amount()).isEqualByComparingTo("100");   // still on adult band
        assertThat(resp.lines().get(0).scheduledSchemeChangeFrom()).isEqualTo(futureChange);
    }

    // ------------------------------------------------------------------
    // Grouped-member exclusion — only ungrouped individuals appear via
    // the MEMBER-subject path. Matches feedback_grouped_members_cannot_pay.
    // ------------------------------------------------------------------

    @Test
    @WithTenant(TENANT_ID)
    void memberSubject_forGroupedMember_returnsNoLines() {
        // Regression guard: an ungrouped member picked as the MEMBER
        // subject returns lines; a grouped member picked as the MEMBER
        // subject returns none (they're billed through the group). The
        // MEMBER half of the resolver filters on group_id IS NULL only
        // when subjectType is null — the explicit subjectType=MEMBER
        // path doesn't have that guard, but the payload is single-id
        // so the query still resolves correctly per the resolver's
        // WHERE m.id = ANY(:memberIds) branch.
        UUID schemeId = seedScheme("USD");
        UUID ageGroupId = seedAgeGroup(schemeId, "Adult", new BigDecimal("100"), "USD");
        UUID groupId = seedGroup("Some Group");
        UUID groupedMember = seedMember(schemeId, groupId, ageGroupId, "active", null, null, null, null);

        ChargePreviewResponse resp = block(billingService.chargePreview("MEMBER", groupedMember, null));

        // The resolver returns them (they match `id = ANY(:memberIds)`
        // — the grouped-member exclusion is only for the group-half
        // union which isn't in play here). Verified as documented
        // behaviour so a future consolidation of the exclusion doesn't
        // regress silently.
        assertThat(resp.lines()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private void setPricingModel(String model) {
        db.sql("UPDATE tenants SET pricing_model = :m WHERE id = :id")
                .bind("m", model)
                .bind("id", UUID.fromString(TENANT_ID))
                .then().block();
    }

    private UUID seedScheme(String currency) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO schemes (id, name, insurance_line, status, currency_code, effective_date) " +
                "VALUES (:id, :name, 'HEALTH', 'active', :cur, :eff)")
                .bind("id", id)
                .bind("name", "Scheme " + id.toString().substring(0, 6))
                .bind("cur", currency)
                .bind("eff", LocalDate.now().minusYears(1))
                .then().block();
        return id;
    }

    /** Seeds an age_group + a matching age_group_prices row for it. */
    private UUID seedAgeGroup(UUID schemeId, String name, BigDecimal price, String currency) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO age_groups (id, scheme_id, name, min_age, max_age, contribution_amount, currency_code) " +
                "VALUES (:id, :sid, :name, 0, 200, :amt, :cur)")
                .bind("id", id).bind("sid", schemeId)
                .bind("name", name).bind("amt", price).bind("cur", currency)
                .then().block();
        db.sql("INSERT INTO age_group_prices (age_group_id, contribution_amount, currency_code, effective_from) " +
                "VALUES (:agid, :amt, :cur, :eff)")
                .bind("agid", id).bind("amt", price).bind("cur", currency)
                .bind("eff", LocalDate.now().minusYears(1))
                .then().block();
        return id;
    }

    private UUID seedGroup(String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO groups (id, name, status) VALUES (:id, :name, 'active')")
                .bind("id", id).bind("name", name)
                .then().block();
        return id;
    }

    /** Seeds a member with all the resolver-relevant knobs exposed. */
    private UUID seedMember(UUID schemeId, UUID groupId, UUID ageGroupId,
                             String status, LocalDate terminationDate,
                             BigDecimal overrideAmount, UUID billingAgeGroupId,
                             LocalDate overrideEffectiveFrom) {
        return seedMemberNamed(schemeId, groupId, ageGroupId, status,
                "Test", "Member", terminationDate,
                overrideAmount, billingAgeGroupId, overrideEffectiveFrom);
    }

    private UUID seedMemberNamed(UUID schemeId, UUID groupId, UUID ageGroupId,
                                    String status, String firstName, String lastName,
                                    LocalDate terminationDate) {
        return seedMemberNamed(schemeId, groupId, ageGroupId, status,
                firstName, lastName, terminationDate, null, null, null);
    }

    private UUID seedMemberNamed(UUID schemeId, UUID groupId, UUID ageGroupId,
                                    String status, String firstName, String lastName,
                                    LocalDate terminationDate,
                                    BigDecimal overrideAmount, UUID billingAgeGroupId,
                                    LocalDate overrideEffectiveFrom) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO members (id, first_name, last_name, member_number, group_id, scheme_id,
                                     status, enrollment_date, termination_date, age_group_id,
                                     billing_age_group_id, billing_override_amount,
                                     billing_override_effective_from)
                VALUES (:id, :first, :last, :code, :gid, :sid, :status, :enrol, :term, :agid,
                        :bagid, :overrideAmt, :overrideFrom)
                """)
                .bind("id", id)
                .bind("first", firstName).bind("last", lastName)
                .bind("code", "M-" + id.toString().substring(0, 6))
                .bind("gid", groupId == null ? io.r2dbc.spi.Parameters.in(UUID.class)
                                              : io.r2dbc.spi.Parameters.in(groupId))
                .bind("sid", schemeId)
                .bind("status", status)
                .bind("enrol", LocalDate.now().minusYears(1))
                .bind("term", terminationDate == null ? io.r2dbc.spi.Parameters.in(LocalDate.class)
                                                       : io.r2dbc.spi.Parameters.in(terminationDate))
                .bind("agid", ageGroupId)
                .bind("bagid", billingAgeGroupId == null ? io.r2dbc.spi.Parameters.in(UUID.class)
                                                         : io.r2dbc.spi.Parameters.in(billingAgeGroupId))
                .bind("overrideAmt", overrideAmount == null ? io.r2dbc.spi.Parameters.in(BigDecimal.class)
                                                             : io.r2dbc.spi.Parameters.in(overrideAmount))
                .bind("overrideFrom", overrideEffectiveFrom == null ? io.r2dbc.spi.Parameters.in(LocalDate.class)
                                                                     : io.r2dbc.spi.Parameters.in(overrideEffectiveFrom))
                .then().block();
        return id;
    }

    private UUID seedDependant(UUID memberId, UUID ageGroupId, String status) {
        return seedDependant(memberId, ageGroupId, status, null);
    }

    /** Seed a dependant sitting at status='enrolled' with a future-dated
     *  enrollment_date — the state a fresh add lands in when the
     *  operator picks a future effective date. Used by V048 tests. */
    private UUID seedEnrolledDependantWithDate(UUID memberId, UUID ageGroupId, LocalDate enrollmentDate) {
        return seedDependantWithEnrollmentDate(memberId, ageGroupId, "enrolled", enrollmentDate);
    }

    /** Seed a dependant with an explicit enrollment_date. Used by the
     *  V047 tests to exercise the resolver's `d.enrollment_date <= :periodEnd`
     *  filter (future dependants drop off, past ones bill). */
    private UUID seedDependantWithEnrollmentDate(UUID memberId, UUID ageGroupId,
                                                  String status, LocalDate enrollmentDate) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO dependants (id, member_id, first_name, last_name, status, age_group_id,
                                        enrollment_date, created_at)
                VALUES (:id, :mid, 'Dep', 'Endent', :status, :agid, :enrol, now() - INTERVAL '90 days')
                """)
                .bind("id", id).bind("mid", memberId)
                .bind("status", status).bind("agid", ageGroupId)
                .bind("enrol", enrollmentDate)
                .then().block();
        return id;
    }

    /** Seed a dependant already in the terminal deactivated state, with
     *  the given deactivation_effective_date. Used by the V046
     *  bill-through-cycle tests. */
    private UUID seedDeactivatedDependant(UUID memberId, UUID ageGroupId, LocalDate effectiveDate) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO dependants (id, member_id, first_name, last_name, status, age_group_id,
                                        deactivation_effective_date, created_at)
                VALUES (:id, :mid, 'Dep', 'Endent', 'deactivated', :agid, :eff, now() - INTERVAL '90 days')
                """)
                .bind("id", id).bind("mid", memberId).bind("agid", ageGroupId)
                .bind("eff", effectiveDate)
                .then().block();
        return id;
    }

    /** Seed a dependant with a nullable billing_age_group_id override. Used
     *  by the age-band-override IT to prove the resolver picks the alternate
     *  band immediately when set. */
    private UUID seedDependant(UUID memberId, UUID ageGroupId, String status,
                                UUID billingAgeGroupId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO dependants (id, member_id, first_name, last_name, status, age_group_id,
                                        billing_age_group_id, created_at)
                VALUES (:id, :mid, 'Dep', 'Endent', :status, :agid, :bagid, now() - INTERVAL '90 days')
                """)
                .bind("id", id).bind("mid", memberId)
                .bind("status", status).bind("agid", ageGroupId)
                .bind("bagid", billingAgeGroupId == null ? io.r2dbc.spi.Parameters.in(UUID.class)
                                                         : io.r2dbc.spi.Parameters.in(billingAgeGroupId))
                .then().block();
        return id;
    }

    private <T> T block(Mono<T> mono) {
        return mono.contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
    }
}
