package com.medfund.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

/**
 * Maps {@link MfaStepUpRequiredException} to a 401 response carrying the
 * {@code x-mfa-required: true} header that the Angular re-auth modal reads
 * to trigger Keycloak step-up.
 *
 * <p>Lives in {@code shared} so every service that carries an MFA-gated
 * endpoint (finance-service submission controller in Phase 5, others later)
 * picks up the same header contract without a per-service copy.
 */
@Slf4j
@RestControllerAdvice
public class MfaStepUpExceptionHandler {

    /** Marker header the Angular re-auth modal watches on 401 responses. */
    public static final String X_MFA_REQUIRED_HEADER = "x-mfa-required";

    @ExceptionHandler(MfaStepUpRequiredException.class)
    public Mono<ResponseEntity<ProblemDetail>> handle(MfaStepUpRequiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "MFA step-up required for this action");
        // The reason is a diagnostic aid — safe to log but not to return in body.
        log.info("[mfa-step-up] rejecting request: {}", ex.stepUpReason());
        return Mono.just(ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .header(X_MFA_REQUIRED_HEADER, "true")
                .body(problem));
    }
}
