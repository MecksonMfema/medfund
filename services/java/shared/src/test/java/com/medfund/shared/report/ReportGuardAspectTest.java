package com.medfund.shared.report;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportGuardAspectTest {

    @Mock
    private ReportEnablementReader reader;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;

    private ReportGuardAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new ReportGuardAspect(reader);
    }

    /** Sample controller-shaped class carrying the annotation. */
    static class Sample {
        @RequiresReport(ReportKey.CREDITORS)
        public Mono<String> monoEndpoint() { return Mono.just("ok"); }

        @RequiresReport(ReportKey.CREDITORS)
        public Flux<String> fluxEndpoint() { return Flux.just("a", "b"); }

        @RequiresReport(ReportKey.CREDITORS)
        public String syncEndpoint() { return "ok"; }
    }

    private void wireMethod(String methodName) throws Exception {
        Method m = Sample.class.getMethod(methodName);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(m);
    }

    @Test
    void mono_proceedsWhenEnabled() throws Throwable {
        wireMethod("monoEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenReturn(Mono.just("ok"));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Mono<?>) result)
                .expectNextMatches("ok"::equals)
                .verifyComplete();
    }

    @Test
    void mono_shortCircuits403WhenDisabled() throws Throwable {
        wireMethod("monoEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(false));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Mono<?>) result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void mono_wrapsThrownExceptionFromProceed() throws Throwable {
        wireMethod("monoEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Mono<?>) result)
                .expectErrorMessage("boom")
                .verify();
    }

    @Test
    void flux_proceedsWhenEnabled() throws Throwable {
        wireMethod("fluxEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenReturn(Flux.just("a", "b"));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Flux<?>) result)
                .expectNextMatches("a"::equals)
                .expectNextMatches("b"::equals)
                .verifyComplete();
    }

    @Test
    void flux_shortCircuits403WhenDisabled() throws Throwable {
        wireMethod("fluxEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(false));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Flux<?>) result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void flux_wrapsThrownExceptionFromProceed() throws Throwable {
        wireMethod("fluxEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenThrow(new IllegalStateException("nope"));

        Object result = aspect.enforce(pjp);
        StepVerifier.create((Flux<?>) result)
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void syncEndpoint_proceedsWhenEnabled() throws Throwable {
        wireMethod("syncEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenReturn("ok");

        Object result = aspect.enforce(pjp);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void syncEndpoint_throwsWhenDisabled() throws Throwable {
        wireMethod("syncEndpoint");
        when(reader.isEnabled(any(ReportKey.class))).thenReturn(Mono.just(false));

        try {
            aspect.enforce(pjp);
        } catch (ResponseStatusException err) {
            assertThat(err.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            return;
        }
        throw new AssertionError("expected ResponseStatusException to be thrown");
    }
}
