package com.medfund.shared.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresReport} at method invocation time. Mirrors the
 * shape of {@code PermissionAspect} — inspect the annotation, look up
 * enablement, short-circuit with 403 or proceed.
 *
 * <p>Reactive return types ({@link Mono}, {@link Flux}) are wrapped so the
 * check runs INSIDE the reactive chain — that's where {@code TenantContext}
 * is visible (populated by {@code TenantWebFilter}).
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ReportGuardAspect {

    private final ReportEnablementReader reader;

    @Around("@annotation(com.medfund.shared.report.RequiresReport)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresReport anno = method.getAnnotation(RequiresReport.class);
        ReportKey key = anno.value();

        Class<?> returnType = method.getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            return reader.isEnabled(key).flatMap(enabled -> {
                if (!enabled) return Mono.error(disabled(key));
                try {
                    return (Mono<?>) pjp.proceed();
                } catch (Throwable t) {
                    return Mono.error(t);
                }
            });
        }
        if (Flux.class.isAssignableFrom(returnType)) {
            return reader.isEnabled(key).flatMapMany(enabled -> {
                if (!enabled) return Flux.error(disabled(key));
                try {
                    return (Flux<?>) pjp.proceed();
                } catch (Throwable t) {
                    return Flux.error(t);
                }
            });
        }
        // Non-reactive controller methods are exceptional in WebFlux — fall
        // through to a blocking check. Mirrors PermissionAspect's stance.
        log.warn("@RequiresReport on non-reactive method {}.{} — synchronous calls cannot read the reactive tenant context",
                method.getDeclaringClass().getSimpleName(), method.getName());
        Boolean enabled = reader.isEnabled(key).block();
        if (Boolean.FALSE.equals(enabled)) throw disabled(key);
        return pjp.proceed();
    }

    private static ResponseStatusException disabled(ReportKey key) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Report is disabled for this tenant: " + key.name());
    }
}
