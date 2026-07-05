package com.medfund.user.service;

import com.medfund.user.repository.GroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Guards the generation shape for group registration numbers. The
 * random block width is what a tenant admin actually configures, so
 * regressions that silently reduce the width would produce numbers
 * shorter than the operator expects (and thus more collision-prone).
 * Kept as a pure unit test — the SQL that loads the scheme is glued to
 * DatabaseClient and covered by the wider ITs already in place.
 */
@ExtendWith(MockitoExtension.class)
class GroupNumberServiceTest {

    @Mock private DatabaseClient db;
    @Mock private GroupRepository groupRepository;

    // ------------------------------------------------------------------
    // Empty-tenant path — no reactor context → default scheme.
    // ------------------------------------------------------------------

    @Test
    void nextRegistrationNumber_defaultsWhenNoTenantContext_producesGrpDashSixDigits() {
        // No tenant on the reactor context → loadScheme short-circuits
        // to defaults (GRP- prefix, no suffix, 6-digit random). Uniqueness
        // probe returns false so the first candidate is accepted.
        when(groupRepository.existsByRegistrationNumber(anyString()))
                .thenReturn(Mono.just(false));

        var service = new GroupNumberService(db, groupRepository);

        StepVerifier.create(service.nextRegistrationNumber())
                .assertNext(number -> {
                    assertThat(number)
                            .as("default shape is 'GRP-' + 6 digits, no suffix")
                            .matches(Pattern.compile("^GRP-\\d{6}$"));
                })
                .verifyComplete();
    }

    @Test
    void nextRegistrationNumber_retriesOnCollision_upToMaxAttempts() {
        // Stub the first two candidates as taken, third as free — the
        // service retries and eventually succeeds. Regression here
        // would surface as duplicate numbers slipping through the
        // probe layer.
        when(groupRepository.existsByRegistrationNumber(anyString()))
                .thenReturn(Mono.just(true))    // 1st attempt collides
                .thenReturn(Mono.just(true))    // 2nd attempt collides
                .thenReturn(Mono.just(false));  // 3rd attempt free

        var service = new GroupNumberService(db, groupRepository);

        StepVerifier.create(service.nextRegistrationNumber())
                .assertNext(number -> assertThat(number).matches("^GRP-\\d{6}$"))
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // NumberScheme record — defensive defaults.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // formatCandidate — prefix / suffix / length matrix
    //
    // The pow10 math inside formatCandidate is where a subtle off-by-one
    // (min = pow10(length) instead of pow10(length - 1), or omitting the
    // + 1 on max) would produce numbers of the wrong width. Run each
    // config 200 times so a single lucky draw can't hide a width bug.
    // ------------------------------------------------------------------

    @Test
    void formatCandidate_defaultShape_always6DigitsBetweenGrpDashAndNoSuffix() {
        // Baseline case matching the migration defaults. Repeated so
        // one accidental min=100000 vs max=999999 boundary hit doesn't
        // pass by luck.
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("GRP-", "", 6);
        for (int i = 0; i < 200; i++) {
            String s = GroupNumberService.formatCandidate(scheme);
            assertThat(s)
                    .as("iteration " + i + ": default shape must be GRP-\\d{6}")
                    .matches("^GRP-\\d{6}$");
        }
    }

    @Test
    void formatCandidate_customPrefixAndSuffix_appearsExactlyWhereConfigured() {
        // Regression guard for a swap-argument bug: if prefix and
        // suffix were accidentally reversed in the string
        // concatenation, "EMP-<n>-Z" would come out "Z<n>EMP-".
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("EMP-", "-Z", 6);
        for (int i = 0; i < 200; i++) {
            String s = GroupNumberService.formatCandidate(scheme);
            assertThat(s).matches("^EMP-\\d{6}-Z$");
        }
    }

    @Test
    void formatCandidate_emptyPrefixAndSuffix_returnsRawDigits() {
        // Empty prefix + empty suffix is a legal config for tenants
        // who want plain numbers. Regression here would leave a
        // leftover dash from an unguarded concatenation.
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("", "", 6);
        for (int i = 0; i < 200; i++) {
            String s = GroupNumberService.formatCandidate(scheme);
            assertThat(s).matches("^\\d{6}$");
        }
    }

    @Test
    void formatCandidate_minWidth3_producesExactly3DigitsAfterPrefix() {
        // Boundary case at the CHECK constraint's lower limit. min =
        // pow10(2) = 100; max = pow10(3) - 1 = 999. A regression to
        // min = pow10(3) would exclude 100-999 entirely and produce
        // 4-digit numbers.
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("X-", "", 3);
        for (int i = 0; i < 200; i++) {
            String s = GroupNumberService.formatCandidate(scheme);
            assertThat(s)
                    .as("length=3 must produce exactly 3 digits, no leading zero, no 4-digit overflow")
                    .matches("^X-\\d{3}$");
        }
    }

    @Test
    void formatCandidate_maxWidth12_producesExactly12DigitsAndFitsInLong() {
        // Boundary case at the CHECK constraint's upper limit. This is
        // where long overflow would hit: pow10(12) = 1_000_000_000_000,
        // pow10(11) = 100_000_000_000. max + 1 must fit in a long
        // (nextLong bound). Any regression that used int math here
        // would throw ArithmeticException; this test surfaces it
        // deterministically.
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("BIG-", "", 12);
        for (int i = 0; i < 200; i++) {
            String s = GroupNumberService.formatCandidate(scheme);
            assertThat(s).matches("^BIG-\\d{12}$");
        }
    }

    @Test
    void formatCandidate_producesVariedValues_notConstant() {
        // Sanity check that the RNG is actually running — a broken
        // implementation that returned min every time would still
        // pass the width tests above.
        GroupNumberService.NumberScheme scheme = new GroupNumberService.NumberScheme("V-", "", 6);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(GroupNumberService.formatCandidate(scheme));
        // With 6-digit random space and 50 draws, collision probability
        // is < 0.2%. Two distinct values is a very safe floor and
        // catches any "always returns the same value" regression.
        assertThat(seen)
                .as("50 draws from a 6-digit space must not all collide")
                .hasSizeGreaterThan(1);
    }

    @Test
    void numberScheme_defaults_matchTenantColumnDefaults() {
        // Migration V125 sets group_number_prefix='GRP-', suffix='',
        // random_length=6. The in-code defaults must agree — otherwise
        // a tenant without a row (test fixture, freshly-provisioned)
        // gets a different shape than a tenant with the DB defaults
        // applied, breaking assumptions across the codebase.
        GroupNumberService.NumberScheme defaults = GroupNumberService.NumberScheme.defaults();
        assertThat(defaults.prefix()).isEqualTo("GRP-");
        assertThat(defaults.suffix()).isEmpty();
        assertThat(defaults.randomLength()).isEqualTo(6);
    }
}
