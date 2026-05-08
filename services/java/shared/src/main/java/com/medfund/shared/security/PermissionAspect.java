package com.medfund.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Implements {@link RequiresPermission} by intercepting annotated methods,
 * inspecting the {@link PermissionContext} populated by
 * {@link PermissionResolverFilter}, and short-circuiting with 403 when the
 * caller's permission set is missing every required key.
 *
 * <p>The 403 surfaces as a Spring {@code ProblemDetail} body via
 * {@code ResponseStatusException} — matches the rest of the platform's
 * error shape so frontends can pull a {@code detail} string off it.
 *
 * <p>Reactive return types ({@link Mono}, {@link Flux}) are wrapped so the
 * permission check happens INSIDE the reactive chain — that's where the
 * context populated by upstream filters is visible. Non-reactive returns
 * (rare in WebFlux services) read the context off the current Reactor scope
 * via deferContextual.
 */
@Slf4j
@Aspect
public class PermissionAspect {

    @Around("@annotation(com.medfund.shared.security.RequiresPermission)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresPermission anno = method.getAnnotation(RequiresPermission.class);
        String[] required = anno.value();

        Class<?> returnType = method.getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            return Mono.deferContextual(ctx -> {
                if (granted(PermissionContext.get(ctx), required)) {
                    try {
                        return (Mono<?>) pjp.proceed();
                    } catch (Throwable t) {
                        return Mono.error(t);
                    }
                }
                return Mono.error(forbidden(required));
            });
        }
        if (Flux.class.isAssignableFrom(returnType)) {
            return Flux.deferContextual(ctx -> {
                if (granted(PermissionContext.get(ctx), required)) {
                    try {
                        return (Flux<?>) pjp.proceed();
                    } catch (Throwable t) {
                        return Flux.error(t);
                    }
                }
                return Flux.error(forbidden(required));
            });
        }
        // Synchronous path — no reactive context to read. Treat as forbidden;
        // permissions only make sense in the request-scoped reactive flow.
        log.warn("@RequiresPermission on non-reactive method {}.{} — synchronous calls cannot read the reactive permission context",
                method.getDeclaringClass().getSimpleName(), method.getName());
        throw forbidden(required);
    }

    private static boolean granted(Set<String> held, String[] required) {
        for (String key : required) {
            if (held.contains(key)) return true;
        }
        return false;
    }

    private static ResponseStatusException forbidden(String[] required) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Missing required permission. One of: " + String.join(", ", required));
    }
}
