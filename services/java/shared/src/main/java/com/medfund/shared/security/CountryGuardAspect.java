package com.medfund.shared.security;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.shared.tenant.TenantMetadataReader;
import com.medfund.shared.tenant.TenantMetadataReader.TenantMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Enforces {@link RequiresCountry} at method invocation time. Mirror image of
 * {@link JurisdictionGuardAspect}: same reactive/non-reactive dispatch,
 * same emit-on-deny, but reads {@code country_code} rather than
 * {@code jurisdiction_code}.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CountryGuardAspect {

    private final TenantMetadataReader metadataReader;
    private final SecurityEventPublisher securityEvents;

    @Around("@annotation(com.medfund.shared.security.RequiresCountry)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresCountry anno = method.getAnnotation(RequiresCountry.class);
        Set<String> allowed = Set.of(anno.value());

        Class<?> returnType = method.getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            return checkThen(allowed).flatMap(ok -> {
                if (!ok) return Mono.error(denied(allowed));
                try {
                    return (Mono<?>) pjp.proceed();
                } catch (Throwable t) {
                    return Mono.error(t);
                }
            });
        }
        if (Flux.class.isAssignableFrom(returnType)) {
            return checkThen(allowed).flatMapMany(ok -> {
                if (!ok) return Flux.error(denied(allowed));
                try {
                    return (Flux<?>) pjp.proceed();
                } catch (Throwable t) {
                    return Flux.error(t);
                }
            });
        }
        log.warn("@RequiresCountry on non-reactive method {}.{} — synchronous calls cannot read the reactive tenant context",
                method.getDeclaringClass().getSimpleName(), method.getName());
        Boolean ok = checkThen(allowed).block();
        if (!Boolean.TRUE.equals(ok)) throw denied(allowed);
        return pjp.proceed();
    }

    private Mono<Boolean> checkThen(Set<String> allowed) {
        return Mono.zip(
                metadataReader.loadFromContext(),
                currentTenantIdString(),
                currentJwt()
        ).flatMap(tuple -> {
            TenantMetadata meta = tuple.getT1();
            String tenantId = tuple.getT2();
            Jwt jwt = tuple.getT3().orElse(null);
            String country = meta.countryCode();
            boolean pass = country != null && allowed.contains(country);
            if (pass) return Mono.just(Boolean.TRUE);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("guard", "RequiresCountry");
            details.put("required", Arrays.toString(allowed.toArray(new String[0])));
            details.put("actualCountry", country);
            return securityEvents.publishAccessDenied(
                            tenantId.isEmpty() ? null : tenantId,
                            AuditActor.id(jwt),
                            AuditActor.email(jwt),
                            "country gate failed",
                            details)
                    .thenReturn(Boolean.FALSE);
        });
    }

    private static Mono<String> currentTenantIdString() {
        return Mono.deferContextual(ctx -> {
            String raw = TenantContext.get(ctx);
            return Mono.just(raw != null ? raw : "");
        });
    }

    private static Mono<java.util.Optional<Jwt>> currentJwt() {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> {
                    var auth = ctx.getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                        return Mono.just(java.util.Optional.of(jwt));
                    }
                    return Mono.just(java.util.Optional.<Jwt>empty());
                })
                .defaultIfEmpty(java.util.Optional.empty());
    }

    private static ResponseStatusException denied(Set<String> allowed) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tenant country not set or does not match required: "
                        + Arrays.toString(allowed.toArray(new String[0])));
    }
}
