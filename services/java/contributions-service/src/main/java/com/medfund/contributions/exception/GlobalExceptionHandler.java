package com.medfund.contributions.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({SchemeNotFoundException.class, ContributionNotFoundException.class,
                       InvoiceNotFoundException.class, NoSuchElementException.class})
    public Mono<ProblemDetail> handleNotFound(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/not-found"));
        problem.setTitle("Resource Not Found");
        return Mono.just(problem);
    }

    @ExceptionHandler({DuplicateSchemeException.class, IllegalStateException.class})
    public Mono<ProblemDetail> handleConflict(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/conflict"));
        problem.setTitle("Conflict");
        return Mono.just(problem);
    }

    @ExceptionHandler(BillingCooldownException.class)
    public Mono<ProblemDetail> handleBillingCooldown(BillingCooldownException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/billing-cooldown"));
        problem.setTitle("Billing Cooldown Active");
        problem.setProperty("remainingMinutes", ex.getRemainingMinutes());
        return Mono.just(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ProblemDetail> handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/bad-request"));
        problem.setTitle("Bad Request");
        return Mono.just(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : "Request failed");
        return Mono.just(problem);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServerWebInputException.class})
    public Mono<ProblemDetail> handleBindingError(Exception ex) {
        log.warn("[bad-input] {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameter");
        problem.setType(URI.create("https://medfund.healthcare/errors/bad-request"));
        problem.setTitle("Bad Request");
        return Mono.just(problem);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> handleValidation(WebExchangeBindException ex) {
        String details = ex.getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
        problem.setType(URI.create("https://medfund.healthcare/errors/validation-failed"));
        problem.setTitle("Validation Failed");
        return Mono.just(problem);
    }

    /** Catch-all so unexpected exceptions never leak class names or stack traces to API clients. */
    @ExceptionHandler(Throwable.class)
    public Mono<ProblemDetail> handleUnexpected(Throwable ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("[unhandled-exception] correlationId={} type={} message={}",
                correlationId, ex.getClass().getName(), ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference: " + correlationId);
        problem.setType(URI.create("https://medfund.healthcare/errors/internal"));
        problem.setTitle("Internal Server Error");
        return Mono.just(problem);
    }
}
