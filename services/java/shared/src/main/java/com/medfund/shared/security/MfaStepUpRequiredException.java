package com.medfund.shared.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown by {@link MfaStepUpGuard} when the caller's JWT has no fresh MFA
 * factor. The mapped 401 response carries an {@code x-mfa-required: true}
 * header (added by {@link MfaStepUpExceptionHandler}); the Angular re-auth
 * modal reads that header to trigger Keycloak step-up.
 *
 * <p>Reason is a short diagnostic — {@code amr=[…] auth_time=…} — kept off
 * the response body so callers can't grep for exact strings.
 */
public class MfaStepUpRequiredException extends ResponseStatusException {

    public MfaStepUpRequiredException(String reason) {
        super(HttpStatus.UNAUTHORIZED, "MFA step-up required for this action");
        this.reason = reason;
    }

    private final String reason;

    public String stepUpReason() {
        return reason;
    }
}
