package com.medfund.user.exception;

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

/**
 * Centralised error responses for user-service.
 *
 * <p>The contract: API responses NEVER contain stack traces or internal type
 * names. Stack traces are logged server-side at WARN/ERROR. Clients receive a
 * {@link ProblemDetail} with status, a short title, and a sanitised message.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MemberNotFoundException.class, DependantNotFoundException.class,
                       ProviderNotFoundException.class, GroupNotFoundException.class,
                       RoleNotFoundException.class, NoSuchElementException.class})
    public Mono<ProblemDetail> handleNotFound(RuntimeException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/not-found"));
        return Mono.just(problem);
    }

    @ExceptionHandler({DuplicateMemberException.class, DuplicateRoleException.class,
                       IllegalStateException.class})
    public Mono<ProblemDetail> handleConflict(RuntimeException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/conflict"));
        return Mono.just(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ProblemDetail> handleBadRequest(IllegalArgumentException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/bad-request"));
        return Mono.just(problem);
    }

    /**
     * Honour the status code that callers attached to a {@link ResponseStatusException}
     * (typical Spring WebFlux idiom — e.g. {@code throw new ResponseStatusException(404, ...)}).
     * Without this handler the catch-all below would re-map them all to 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : "Request failed");
        return Mono.just(problem);
    }

    /**
     * Path variable / query parameter type conversion failures (e.g. a non-UUID
     * value bound to a UUID @PathVariable) and other request-input issues.
     * Returns a sanitised 400 — no Spring class names, no stack trace.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServerWebInputException.class})
    public Mono<ProblemDetail> handleBindingError(Exception ex) {
        log.warn("[bad-input] {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid request parameter");
        problem.setType(URI.create("https://medfund.healthcare/errors/bad-request"));
        return Mono.just(problem);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> handleValidation(WebExchangeBindException ex) {
        String errors = ex.getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining(", "));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
        problem.setType(URI.create("https://medfund.healthcare/errors/validation"));
        return Mono.just(problem);
    }

    /**
     * Catch-all so unexpected exceptions never leak class names or stack traces
     * to API clients. Full trace is logged server-side with a correlation id
     * the client can quote when reporting the error.
     */
    @ExceptionHandler(Throwable.class)
    public Mono<ProblemDetail> handleUnexpected(Throwable ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("[unhandled-exception] correlationId={} type={} message={}",
                correlationId, ex.getClass().getName(), ex.getMessage(), ex);
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference: " + correlationId);
        problem.setType(URI.create("https://medfund.healthcare/errors/internal"));
        return Mono.just(problem);
    }
}
