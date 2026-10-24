package com.medfund.shared.security;

import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.shared.entitlement.TenantEntitlementRegistry;
import com.medfund.shared.tenant.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementAspectTest {

    @Mock private TenantEntitlementRegistry registry;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature signature;

    private static final UUID TENANT = UUID.randomUUID();

    static class MethodAnnotated {
        @RequiresEntitlement(ModuleKey.KYC)
        public Mono<String> mono() { return Mono.just("ok"); }

        @RequiresEntitlement(ModuleKey.KYC)
        public Flux<String> flux() { return Flux.just("a"); }
    }

    // Class-level annotation (the shape VerificationController uses).
    @RequiresEntitlement(ModuleKey.KYC)
    static class ClassAnnotated {
        public Mono<String> mono() { return Mono.just("ok"); }
    }

    private EntitlementAspect aspect() { return new EntitlementAspect(registry); }

    private void wire(Class<?> owner, String name) throws Exception {
        Method m = owner.getMethod(name);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(m);
    }

    private static Context withTenant(String tenantId) {
        return TenantContext.put(Context.empty(), tenantId);
    }

    @Test
    void mono_proceedsWhenEntitled() throws Throwable {
        wire(MethodAnnotated.class, "mono");
        when(registry.isEntitled(TENANT, ModuleKey.KYC)).thenReturn(Mono.just(true));
        when(pjp.proceed()).thenReturn(Mono.just("ok"));

        StepVerifier.create(((Mono<?>) aspect().enforce(pjp))
                        .contextWrite(ctx -> withTenant(TENANT.toString())))
                .expectNextMatches("ok"::equals)
                .verifyComplete();
    }

    @Test
    void mono_denies403WhenNotEntitled() throws Throwable {
        wire(MethodAnnotated.class, "mono");
        when(registry.isEntitled(TENANT, ModuleKey.KYC)).thenReturn(Mono.just(false));

        StepVerifier.create(((Mono<?>) aspect().enforce(pjp))
                        .contextWrite(ctx -> withTenant(TENANT.toString())))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    var rse = (ResponseStatusException) err;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("KYC");
                })
                .verify();

        verify(pjp, never()).proceed();
    }

    @Test
    void mono_denies403WhenNoTenantInContext() throws Throwable {
        wire(MethodAnnotated.class, "mono");

        // No tenant on the context — a per-tenant module can't be entitled.
        StepVerifier.create(((Mono<?>) aspect().enforce(pjp)))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(registry, never()).isEntitled(any(), any());
        verify(pjp, never()).proceed();
    }

    @Test
    void flux_denies403WhenNotEntitled() throws Throwable {
        wire(MethodAnnotated.class, "flux");
        when(registry.isEntitled(TENANT, ModuleKey.KYC)).thenReturn(Mono.just(false));

        StepVerifier.create(((Flux<?>) aspect().enforce(pjp))
                        .contextWrite(ctx -> withTenant(TENANT.toString())))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void classLevelAnnotation_isHonoured() throws Throwable {
        // The annotation is on the class, not the method — proves @within resolution.
        wire(ClassAnnotated.class, "mono");
        when(registry.isEntitled(eq(TENANT), eq(ModuleKey.KYC))).thenReturn(Mono.just(false));

        StepVerifier.create(((Mono<?>) aspect().enforce(pjp))
                        .contextWrite(ctx -> withTenant(TENANT.toString())))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }
}
