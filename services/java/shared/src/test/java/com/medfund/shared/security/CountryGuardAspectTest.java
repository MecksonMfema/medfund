package com.medfund.shared.security;

import com.medfund.shared.tenant.TenantContext;
import com.medfund.shared.tenant.TenantMetadataReader;
import com.medfund.shared.tenant.TenantMetadataReader.TenantMetadata;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryGuardAspectTest {

    @Mock
    private TenantMetadataReader metadataReader;
    @Mock
    private SecurityEventPublisher securityEvents;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;

    @Captor
    private ArgumentCaptor<Map<String, Object>> detailsCaptor;

    private CountryGuardAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CountryGuardAspect(metadataReader, securityEvents);
        lenient().when(securityEvents.publishAccessDenied(
                any(), any(), any(), anyString(), any())).thenReturn(Mono.empty());
    }

    static class Sample {
        @RequiresCountry({"ZW", "ZA"})
        public Mono<String> mono() { return Mono.just("ok"); }

        @RequiresCountry({"US"})
        public Flux<String> flux() { return Flux.just("a"); }

        @RequiresCountry({"ZW"})
        public String sync() { return "ok"; }
    }

    private void wireMethod(String name) throws Exception {
        Method m = Sample.class.getMethod(name);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(m);
    }

    @Test
    void mono_proceedsOnMatchingCountry() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata(null, "ZW")));
        when(pjp.proceed()).thenReturn(Mono.just("ok"));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectNextMatches("ok"::equals)
                .verifyComplete();

        verify(securityEvents, never()).publishAccessDenied(any(), any(), any(), anyString(), any());
    }

    @Test
    void mono_denies403WhenNullCountry() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(TenantMetadata.empty()));

        UUID tenantId = UUID.randomUUID();
        StepVerifier.create(((Mono<?>) aspect.enforce(pjp))
                        .contextWrite(ctx -> TenantContext.put(Context.of(ctx), tenantId.toString())))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    var rse = (ResponseStatusException) err;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("ZW");
                })
                .verify();

        verify(securityEvents).publishAccessDenied(
                eq(tenantId.toString()), any(), any(),
                eq("country gate failed"),
                detailsCaptor.capture());
        assertThat(detailsCaptor.getValue()).containsEntry("guard", "RequiresCountry");
        assertThat(detailsCaptor.getValue().get("actualCountry")).isNull();
    }

    @Test
    void mono_denies403WhenNonMatchingCountry() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata(null, "US")));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(securityEvents).publishAccessDenied(
                eq(null), any(), any(), eq("country gate failed"), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().get("actualCountry")).isEqualTo("US");
    }

    @Test
    void flux_denies403WhenNonMatchingCountry() throws Throwable {
        wireMethod("flux");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata(null, "ZW")));

        StepVerifier.create(((Flux<?>) aspect.enforce(pjp)))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void sync_proceedsOnMatch() throws Throwable {
        wireMethod("sync");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata(null, "ZW")));
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.enforce(pjp)).isEqualTo("ok");
    }
}
