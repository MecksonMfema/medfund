package com.medfund.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * MFA step-up gate for high-privilege mutations (Phase 16 §0 REG13 —
 * regulatory submissions). Passes when the caller's JWT proves an MFA
 * factor was used AND the {@code auth_time} claim is within
 * {@link #MFA_FRESHNESS} of now; throws {@link MfaStepUpRequiredException}
 * otherwise (mapped to 401 + {@code x-mfa-required: true} header by
 * {@link MfaStepUpExceptionHandler}).
 *
 * <p>Recognised {@code amr} values: {@code mfa}, {@code otp}, {@code totp},
 * {@code hwk}. Keycloak emits {@code totp} for authenticator apps,
 * {@code otp} for email/SMS OTP, {@code hwk} for hardware WebAuthn — the
 * union covers every method the platform supports.
 *
 * <p>Callers are expected to be reactive; a synchronous {@link #requireStepUp}
 * variant is not provided because every regulator-submission endpoint is
 * WebFlux.
 */
@Slf4j
@Component
public class MfaStepUpGuard {

    /** {@code amr} claim values that count as an MFA factor. */
    private static final Set<String> MFA_AMR_VALUES = Set.of("mfa", "otp", "totp", "hwk");

    /** Max delta between {@code auth_time} and now for a JWT to satisfy step-up. */
    public static final Duration MFA_FRESHNESS = Duration.ofMinutes(5);

    /**
     * Emits {@link Mono#empty()} when the JWT proves MFA within
     * {@link #MFA_FRESHNESS}; emits {@link MfaStepUpRequiredException}
     * otherwise. Null JWT denies.
     */
    public Mono<Void> requireStepUp(Jwt jwt) {
        if (jwt == null) {
            return Mono.error(new MfaStepUpRequiredException("no jwt in context"));
        }
        List<String> amr = jwt.getClaimAsStringList("amr");
        Instant authTime = jwt.getClaimAsInstant("auth_time");
        boolean hasMfa = amr != null && amr.stream().anyMatch(MFA_AMR_VALUES::contains);
        boolean isFresh = authTime != null
                && Duration.between(authTime, Instant.now()).abs().compareTo(MFA_FRESHNESS) <= 0;
        if (hasMfa && isFresh) return Mono.empty();
        String reason = "amr=" + amr + " auth_time=" + authTime;
        log.debug("[mfa-step-up] denied: {}", reason);
        return Mono.error(new MfaStepUpRequiredException(reason));
    }
}
