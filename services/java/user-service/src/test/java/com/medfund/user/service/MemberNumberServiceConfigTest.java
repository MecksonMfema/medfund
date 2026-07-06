package com.medfund.user.service;

import com.medfund.user.entity.Member;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberRepository;
import com.medfund.user.service.MemberNumberService.MemberNumberConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Guards the shape rules for tenant-configurable member numbers.
 *
 * <p>The DB knobs (V126) meet the code here via the
 * {@link MemberNumberConfig} record; the loader path is glued to
 * DatabaseClient and covered by the wider ITs already in place. Unit
 * tests focus on the number-shape helpers where a subtle off-by-one
 * in the pow10 math, an argument swap in the concatenation, or a
 * separator-mismatch in stripSuffix would silently produce numbers
 * of the wrong shape.
 */
@ExtendWith(MockitoExtension.class)
class MemberNumberServiceConfigTest {

    @Mock private DatabaseClient db;
    @Mock private MemberRepository memberRepository;
    @Mock private DependantRepository dependantRepository;

    // ------------------------------------------------------------------
    // Empty-tenant path — no reactor context → defaults.
    // ------------------------------------------------------------------

    @Test
    void nextMemberNumber_defaultsWhenNoTenantContext_producesMbrDashSixDigits() {
        // No tenant in the reactor context → loadConfig short-circuits
        // to defaults (INDEPENDENT scheme, MBR- prefix, 6-digit random).
        // Uniqueness probes return false so the first candidate is kept.
        // existsAcross probes BOTH tables sequentially, so stub both.
        when(memberRepository.existsByMemberNumber(anyString()))
                .thenReturn(Mono.just(false));
        when(dependantRepository.existsByMemberNumber(anyString()))
                .thenReturn(Mono.just(false));

        var service = new MemberNumberService(db, memberRepository, dependantRepository);

        StepVerifier.create(service.nextMemberNumber())
                .assertNext(number -> assertThat(number)
                        .as("default shape is 'MBR-' + 6 digits, no suffix")
                        .matches(Pattern.compile("^MBR-\\d{6}$")))
                .verifyComplete();
    }

    @Test
    void nextDependantNumber_defaultsWhenNoTenantContext_producesDepDashSixDigits() {
        // Same default path as the member case but through the
        // dependant generator. Guards against a swapped-prefix
        // regression where both paths use the same constant.
        when(memberRepository.existsByMemberNumber(anyString()))
                .thenReturn(Mono.just(false));
        when(dependantRepository.existsByMemberNumber(anyString()))
                .thenReturn(Mono.just(false));

        Member parent = new Member();
        parent.setId(UUID.randomUUID());
        parent.setMemberNumber("MBR-123456"); // no suffix → INDEPENDENT fallback anyway

        var service = new MemberNumberService(db, memberRepository, dependantRepository);

        StepVerifier.create(service.nextDependantNumber(parent))
                .assertNext(number -> assertThat(number)
                        .as("default dependant shape is 'DEP-' + 6 digits")
                        .matches(Pattern.compile("^DEP-\\d{6}$")))
                .verifyComplete();
    }

    @Test
    void nextDependantNumber_nullParent_errors() {
        // Guard the API surface — a null parent should surface a
        // meaningful IllegalArgumentException, not a downstream NPE.
        var service = new MemberNumberService(db, memberRepository, dependantRepository);

        StepVerifier.create(service.nextDependantNumber(null))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("parent"))
                .verify();
    }

    // ------------------------------------------------------------------
    // MemberNumberConfig — defaults must match the V126 column defaults
    // ------------------------------------------------------------------

    @Test
    void defaultConfig_matchesV126ColumnDefaults() {
        // If a tenant row is missing (test fixture, freshly-provisioned
        // via V126) the loader falls back to DEFAULT_CONFIG. Those
        // values must match the DB defaults exactly — otherwise a
        // fixture-based tenant behaves differently from a real-row
        // tenant that never touches the knobs.
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(cfg.scheme()).isEqualTo("INDEPENDENT");
        assertThat(cfg.memberPrefix()).isEqualTo("MBR-");
        assertThat(cfg.dependantPrefix()).isEqualTo("DEP-");
        assertThat(cfg.randomLength()).isEqualTo(6);
        assertThat(cfg.suffixSeparator()).isEqualTo("-");
        assertThat(cfg.suffixPadding()).isEqualTo(2);
        assertThat(cfg.suffixStart()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // randomBlock — width matrix
    //
    // pow10 math where a subtle off-by-one would produce numbers of
    // the wrong width. Run each config 200 times so a single lucky
    // draw can't hide a width bug.
    // ------------------------------------------------------------------

    @Test
    void randomBlock_defaultWidth6_alwaysExactly6Digits() {
        for (int i = 0; i < 200; i++) {
            String s = MemberNumberService.randomBlock(6);
            assertThat(s).as("iteration " + i)
                    .matches("^\\d{6}$");
        }
    }

    @Test
    void randomBlock_minWidth3_producesExactly3Digits() {
        // Boundary case at the CHECK constraint's lower limit. min =
        // pow10(2) = 100; max = pow10(3) = 1000 (exclusive). Regression
        // to min = pow10(3) would exclude 100-999 entirely and produce
        // 4-digit numbers.
        for (int i = 0; i < 200; i++) {
            String s = MemberNumberService.randomBlock(3);
            assertThat(s).as("iteration " + i)
                    .matches("^\\d{3}$");
        }
    }

    @Test
    void randomBlock_maxWidth12_producesExactly12DigitsAndFitsInLong() {
        // Boundary case at the CHECK constraint's upper limit. This
        // is where long overflow would hit — pow10(12) is beyond
        // int range. Any regression that used int math would throw
        // ArithmeticException here.
        for (int i = 0; i < 200; i++) {
            String s = MemberNumberService.randomBlock(12);
            assertThat(s).as("iteration " + i)
                    .matches("^\\d{12}$");
        }
    }

    @Test
    void randomBlock_producesVariedValues_notConstant() {
        // Sanity — a broken implementation that returned min every
        // time would still pass the width tests above.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(MemberNumberService.randomBlock(6));
        assertThat(seen)
                .as("50 draws from a 6-digit space must not all collide")
                .hasSizeGreaterThan(1);
    }

    // ------------------------------------------------------------------
    // stripSuffix — separator + padding matrix
    //
    // The dependant-suffix generator relies on stripSuffix to find the
    // parent's base portion. A regression here would either fall back
    // to INDEPENDENT (breaking SHARED_WITH_SUFFIX household grouping)
    // or produce garbage bases like "MBR-83012" when the number is
    // "MBR-483012_01" with a custom '_' separator.
    // ------------------------------------------------------------------

    @Test
    void stripSuffix_defaultConfig_dashSeparatorTwoDigits_stripsCorrectly() {
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(MemberNumberService.stripSuffix("MBR-483012-01", cfg)).isEqualTo("MBR-483012");
        assertThat(MemberNumberService.stripSuffix("MBR-483012-42", cfg)).isEqualTo("MBR-483012");
    }

    @Test
    void stripSuffix_customUnderscoreSeparator_stripsCorrectly() {
        // Custom-shape tenant: MED- prefix, _ separator, 4-digit padding.
        MemberNumberConfig cfg = new MemberNumberConfig(
                "SHARED_WITH_SUFFIX", "MED-", "DEP-", 6, "_", 4, 1);
        assertThat(MemberNumberService.stripSuffix("MED-483012_0001", cfg)).isEqualTo("MED-483012");
        assertThat(MemberNumberService.stripSuffix("MED-483012_9999", cfg)).isEqualTo("MED-483012");
    }

    @Test
    void stripSuffix_nonMatchingPadding_returnsNull() {
        // "MBR-483012-1" has a 1-digit suffix but the config demands 2.
        // Must return null so the generator falls back to INDEPENDENT
        // rather than producing an incorrect base.
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(MemberNumberService.stripSuffix("MBR-483012-1", cfg)).isNull();
    }

    @Test
    void stripSuffix_nonDigitTail_returnsNull() {
        // Two-char tail that isn't all digits — must not match.
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(MemberNumberService.stripSuffix("MBR-483012-XY", cfg)).isNull();
    }

    @Test
    void stripSuffix_nullOrLegacyNumber_returnsNull() {
        // Null / legacy INDEPENDENT number → returns null so the
        // generator triggers the INDEPENDENT fallback with a WARN log.
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(MemberNumberService.stripSuffix(null, cfg)).isNull();
        assertThat(MemberNumberService.stripSuffix("MBR-483012", cfg)).isNull();
    }

    @Test
    void stripSuffix_prefixOnlySeparator_returnsNull() {
        // "-01" has lastIndexOf('-') = 0 → base would be empty string.
        // Guard: idx <= 0 returns null so we never produce an empty base.
        MemberNumberConfig cfg = MemberNumberService.DEFAULT_CONFIG;
        assertThat(MemberNumberService.stripSuffix("-01", cfg)).isNull();
    }
}
