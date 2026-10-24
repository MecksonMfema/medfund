package com.medfund.shared.security;

import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.shared.entitlement.TenantEntitlementRegistry;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Implements {@link RequiresEntitlement} by intercepting annotated methods (or
 * methods of annotated classes), resolving the tenant from the Reactor context
 * populated by the tenant web filter, and short-circuiting with 403 when the
 * tenant is not entitled to the required {@link ModuleKey}.
 *
 * <p>Mirrors {@code PermissionAspect}: the check happens INSIDE the reactive
 * chain ({@code deferContextual}), which is the only place the tenant context
 * from upstream filters is visible. Fails closed — a missing tenant, or any
 * registry uncertainty, denies (the registry's own default is {@code false}).
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class EntitlementAspect {

    private final TenantEntitlementRegistry registry;

    @Around("@annotation(com.medfund.shared.security.RequiresEntitlement) "
            + "|| @within(com.medfund.shared.security.RequiresEntitlement)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresEntitlement anno = resolve(method);
        if (anno == null) {
            // Pointcut matched but no annotation found (should not happen) —
            // proceed rather than fail an un-annotated call.
            return pjp.proceed();
        }
        ModuleKey module = anno.value();

        Class<?> returnType = method.getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            return Mono.deferContextual(ctx -> check(ctx, module)
                    .flatMap(entitled -> {
                        if (entitled) {
                            try {
                                return (Mono<?>) pjp.proceed();
                            } catch (Throwable t) {
                                return Mono.error(t);
                            }
                        }
                        return Mono.error(forbidden(module));
                    }));
        }
        if (Flux.class.isAssignableFrom(returnType)) {
            return Flux.deferContextual(ctx -> check(ctx, module)
                    .flatMapMany(entitled -> {
                        if (entitled) {
                            try {
                                return (Flux<?>) pjp.proceed();
                            } catch (Throwable t) {
                                return Flux.error(t);
                            }
                        }
                        return Flux.error(forbidden(module));
                    }));
        }
        // Synchronous path — no reactive context to read. Treat as forbidden;
        // entitlement checks only make sense in the request-scoped reactive flow.
        log.warn("@RequiresEntitlement on non-reactive method {}.{} — synchronous calls cannot read the reactive tenant context",
                method.getDeclaringClass().getSimpleName(), method.getName());
        throw forbidden(module);
    }

    private Mono<Boolean> check(ContextView ctx, ModuleKey module) {
        String raw = TenantContext.get(ctx);
        UUID tenantId;
        try {
            tenantId = raw != null ? UUID.fromString(raw) : null;
        } catch (IllegalArgumentException e) {
            tenantId = null;
        }
        if (tenantId == null) {
            // No tenant (or the "platform" sentinel) — a per-tenant module
            // cannot be entitled without one. Fail closed.
            return Mono.just(false);
        }
        return registry.isEntitled(tenantId, module);
    }

    private static RequiresEntitlement resolve(Method method) {
        RequiresEntitlement onMethod = method.getAnnotation(RequiresEntitlement.class);
        if (onMethod != null) return onMethod;
        return method.getDeclaringClass().getAnnotation(RequiresEntitlement.class);
    }

    private static ResponseStatusException forbidden(ModuleKey module) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "module not enabled: " + module.name());
    }
}
