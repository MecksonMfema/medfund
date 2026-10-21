package com.medfund.shared.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingCurrencyResolverTest {

    @Mock
    private DatabaseClient db;
    @Mock
    private DatabaseClient.GenericExecuteSpec spec;

    private ReportingCurrencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReportingCurrencyResolver(db);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    @SuppressWarnings("unchecked")
    private RowsFetchSpec<String> stubFetch() {
        RowsFetchSpec<String> fetch = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(BiFunction.class))).thenReturn(fetch);
        return fetch;
    }

    @Test
    void resolve_overrideWins() {
        StepVerifier.create(resolver.resolve(UUID.randomUUID(), "zwl"))
                .expectNext("ZWL")
                .verifyComplete();
    }

    @Test
    void resolve_trimsAndUppercasesOverride() {
        StepVerifier.create(resolver.resolve(UUID.randomUUID(), "  usd  "))
                .expectNext("USD")
                .verifyComplete();
    }

    @Test
    void resolve_fallsBackToTenantDefault() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just("EUR"));

        StepVerifier.create(resolver.resolve(UUID.randomUUID(), null))
                .expectNext("EUR")
                .verifyComplete();
    }

    @Test
    void resolve_blankOverrideTreatedAsMissing() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just("USD"));

        StepVerifier.create(resolver.resolve(UUID.randomUUID(), "   "))
                .expectNext("USD")
                .verifyComplete();
    }

    @Test
    void resolve_fallsBackToPlatformDefaultWhenNoRow() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.empty());

        StepVerifier.create(resolver.resolve(UUID.randomUUID(), null))
                .expectNext(ReportingCurrencyResolver.PLATFORM_DEFAULT)
                .verifyComplete();
    }

    @Test
    void resolve_returnsPlatformDefaultOnNullTenant() {
        StepVerifier.create(resolver.resolve(null, null))
                .expectNext(ReportingCurrencyResolver.PLATFORM_DEFAULT)
                .verifyComplete();
    }

    @Test
    void getDefaultCurrencyCode_returnsPlatformDefaultOnDbError() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(resolver.getDefaultCurrencyCode(UUID.randomUUID()))
                .expectNext(ReportingCurrencyResolver.PLATFORM_DEFAULT)
                .verifyComplete();
    }

    @Test
    void getDefaultCurrencyCode_returnsPlatformDefaultOnNullTenant() {
        StepVerifier.create(resolver.getDefaultCurrencyCode(null))
                .expectNext(ReportingCurrencyResolver.PLATFORM_DEFAULT)
                .verifyComplete();
    }
}
