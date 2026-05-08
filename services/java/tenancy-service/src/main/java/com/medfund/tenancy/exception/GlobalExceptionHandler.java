package com.medfund.tenancy.exception;

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

    @ExceptionHandler(TenantNotFoundException.class)
    public Mono<ProblemDetail> handleNotFound(TenantNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/tenant-not-found"));
        return Mono.just(problem);
    }

    @ExceptionHandler(TenantSlugConflictException.class)
    public Mono<ProblemDetail> handleConflict(TenantSlugConflictException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/tenant-slug-conflict"));
        return Mono.just(problem);
    }

    @ExceptionHandler(CurrencyConflictException.class)
    public Mono<ProblemDetail> handleCurrencyConflict(CurrencyConflictException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/currency-conflict"));
        return Mono.just(problem);
    }

    @ExceptionHandler(ExchangeRateNotFoundException.class)
    public Mono<ProblemDetail> handleRateNotFound(ExchangeRateNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/exchange-rate-not-found"));
        return Mono.just(problem);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public Mono<ProblemDetail> handleNoSuchElement(NoSuchElementException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://medfund.healthcare/errors/not-found"));
        return Mono.just(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ProblemDetail> handleIllegalState(IllegalStateException ex) {
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

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : "Request failed");
        return Mono.just(problem);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServerWebInputException.class})
    public Mono<ProblemDetail> handleBindingError(Exception ex) {
        log.warn("[bad-input] {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameter");
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

    /** Catch-all so unexpected exceptions never leak class names or stack traces to API clients. */
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
