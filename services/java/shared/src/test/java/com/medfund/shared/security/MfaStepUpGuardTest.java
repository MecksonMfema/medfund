package com.medfund.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MfaStepUpGuardTest {

    private final MfaStepUpGuard guard = new MfaStepUpGuard();

    private static Jwt jwt(List<String> amr, Instant authTime) {
        Jwt.Builder b = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-1");
        if (amr != null) b = b.claim("amr", amr);
        if (authTime != null) b = b.claim("auth_time", authTime);
        return b.build();
    }

    @Test
    void passesWhenAmrHasMfaFactorAndAuthTimeIsFresh() {
        for (String factor : List.of("mfa", "otp", "totp", "hwk")) {
            Jwt token = jwt(List.of(factor, "pwd"), Instant.now().minus(1, ChronoUnit.MINUTES));
            StepVerifier.create(guard.requireStepUp(token)).verifyComplete();
        }
    }

    @Test
    void deniesWhenJwtIsNull() {
        StepVerifier.create(guard.requireStepUp(null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(MfaStepUpRequiredException.class);
                    assertThat(((MfaStepUpRequiredException) err).stepUpReason()).contains("no jwt");
                })
                .verify();
    }

    @Test
    void deniesWhenAmrHasNoMfaFactor() {
        Jwt token = jwt(List.of("pwd"), Instant.now());
        StepVerifier.create(guard.requireStepUp(token))
                .expectError(MfaStepUpRequiredException.class)
                .verify();
    }

    @Test
    void deniesWhenAmrClaimAbsent() {
        Jwt token = jwt(null, Instant.now());
        StepVerifier.create(guard.requireStepUp(token))
                .expectError(MfaStepUpRequiredException.class)
                .verify();
    }

    @Test
    void deniesWhenAuthTimeClaimAbsent() {
        Jwt token = jwt(List.of("totp"), null);
        StepVerifier.create(guard.requireStepUp(token))
                .expectError(MfaStepUpRequiredException.class)
                .verify();
    }

    @Test
    void deniesWhenAuthTimeOlderThanFreshnessWindow() {
        // 6 minutes > 5-minute freshness window
        Jwt token = jwt(List.of("totp"), Instant.now().minus(6, ChronoUnit.MINUTES));
        StepVerifier.create(guard.requireStepUp(token))
                .expectError(MfaStepUpRequiredException.class)
                .verify();
    }

    @Test
    void freshnessConstantIsExactlyFiveMinutes() {
        // Locked in per the plan REG13 — a shorter window disrupts genuine workflows,
        // a longer window weakens the gate.
        assertThat(MfaStepUpGuard.MFA_FRESHNESS.toMinutes()).isEqualTo(5);
    }

    @Test
    void mfaStepUpRequiredException_isResponseStatus401() {
        var ex = new MfaStepUpRequiredException("test");
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(Map.of("reason", ex.stepUpReason())).containsEntry("reason", "test");
    }
}
