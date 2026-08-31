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
class JurisdictionGuardAspectTest {

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

    private JurisdictionGuardAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new JurisdictionGuardAspect(metadataReader, securityEvents);
        // Denial branch fires the publisher; keep it happy across all tests.
        lenient().when(securityEvents.publishAccessDenied(
                any(), any(), any(), anyString(), any())).thenReturn(Mono.empty());
    }

    /** Sample controller carrying the annotation for reflection. */
    static class Sample {
        @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
        public Mono<String> mono() { return Mono.just("ok"); }

        @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM", "ZA_CMS_MEDICAL_SCHEME"})
        public Mono<String> monoMulti() { return Mono.just("ok"); }

        @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
        public Flux<String> flux() { return Flux.just("a", "b"); }

        @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
        public String sync() { return "ok"; }
    }

    private void wireMethod(String name) throws Exception {
        Method m = Sample.class.getMethod(name);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(m);
    }

    @Test
    void mono_proceedsOnMatch() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZW_IPEC_SHORT_TERM", "ZW")));
        when(pjp.proceed()).thenReturn(Mono.just("ok"));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectNextMatches("ok"::equals)
                .verifyComplete();

        verify(securityEvents, never()).publishAccessDenied(any(), any(), any(), anyString(), any());
    }

    @Test
    void mono_multiValue_proceedsWhenTenantMatchesAny() throws Throwable {
        wireMethod("monoMulti");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZA_CMS_MEDICAL_SCHEME", "ZA")));
        when(pjp.proceed()).thenReturn(Mono.just("ok"));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectNextMatches("ok"::equals)
                .verifyComplete();
    }

    @Test
    void mono_denies403WhenNullJurisdiction() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(TenantMetadata.empty()));

        UUID tenantId = UUID.randomUUID();
        StepVerifier.create(((Mono<?>) aspect.enforce(pjp))
                        .contextWrite(ctx -> TenantContext.put(Context.of(ctx), tenantId.toString())))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    var rse = (ResponseStatusException) err;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("ZW_IPEC_SHORT_TERM");
                })
                .verify();

        verify(pjp, never()).proceed();
        verify(securityEvents).publishAccessDenied(
                eq(tenantId.toString()), any(), any(),
                eq("jurisdiction gate failed"),
                detailsCaptor.capture());
        Map<String, Object> details = detailsCaptor.getValue();
        assertThat(details).containsEntry("guard", "RequiresJurisdiction");
        assertThat(details.get("actualJurisdiction")).isNull();
        assertThat(String.valueOf(details.get("required"))).contains("ZW_IPEC_SHORT_TERM");
    }

    @Test
    void mono_denies403WhenNonMatchingJurisdiction() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("US_NAIC", "US")));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();

        verify(securityEvents).publishAccessDenied(
                eq(null), any(), any(), eq("jurisdiction gate failed"), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().get("actualJurisdiction")).isEqualTo("US_NAIC");
    }

    @Test
    void mono_wrapsThrownExceptionFromProceed() throws Throwable {
        wireMethod("mono");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZW_IPEC_SHORT_TERM", "ZW")));
        when(pjp.proceed()).thenThrow(new RuntimeException("boom"));

        StepVerifier.create(((Mono<?>) aspect.enforce(pjp)))
                .expectErrorMessage("boom")
                .verify();
    }

    @Test
    void flux_proceedsOnMatch() throws Throwable {
        wireMethod("flux");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZW_IPEC_SHORT_TERM", "ZW")));
        when(pjp.proceed()).thenReturn(Flux.just("a", "b"));

        StepVerifier.create(((Flux<?>) aspect.enforce(pjp)))
                .expectNextMatches("a"::equals)
                .expectNextMatches("b"::equals)
                .verifyComplete();
    }

    @Test
    void flux_deniesOnMismatch() throws Throwable {
        wireMethod("flux");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZA_CMS_MEDICAL_SCHEME", "ZA")));

        StepVerifier.create(((Flux<?>) aspect.enforce(pjp)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void sync_throwsWhenNoMatch() throws Throwable {
        wireMethod("sync");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(TenantMetadata.empty()));

        try {
            aspect.enforce(pjp);
        } catch (ResponseStatusException err) {
            assertThat(err.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            return;
        }
        throw new AssertionError("expected 403 to be thrown");
    }

    @Test
    void sync_proceedsWhenMatch() throws Throwable {
        wireMethod("sync");
        when(metadataReader.loadFromContext()).thenReturn(Mono.just(new TenantMetadata("ZW_IPEC_SHORT_TERM", "ZW")));
        when(pjp.proceed()).thenReturn("ok");

        assertThat(aspect.enforce(pjp)).isEqualTo("ok");
    }
}
