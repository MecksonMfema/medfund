package com.medfund.shared.report;

import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportEnablementReaderTest {

    @Mock
    private DatabaseClient db;
    @Mock
    private DatabaseClient.GenericExecuteSpec spec;

    private ReportEnablementReader reader;

    @BeforeEach
    void setUp() {
        reader = new ReportEnablementReader(db);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    /** Convenience: swap the fetch that {@code spec.map(...)} returns. */
    @SuppressWarnings("unchecked")
    private <T> RowsFetchSpec<T> stubFetch() {
        RowsFetchSpec<T> fetch = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(BiFunction.class))).thenReturn(fetch);
        return fetch;
    }

    @Test
    void isEnabled_returnsPersistedRow() {
        RowsFetchSpec<Boolean> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just(Boolean.FALSE));

        StepVerifier.create(reader.isEnabled(UUID.randomUUID(), ReportKey.CREDITORS))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void isEnabled_defaultsToTrueWhenNoRow() {
        RowsFetchSpec<Boolean> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.empty());

        StepVerifier.create(reader.isEnabled(UUID.randomUUID(), ReportKey.CREDITORS))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void isEnabled_shortCircuitsOnNullInputs() {
        StepVerifier.create(reader.isEnabled(null, ReportKey.CREDITORS))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
        StepVerifier.create(reader.isEnabled(UUID.randomUUID(), null))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void isEnabled_swallowsDbErrorsAsEnabled() {
        RowsFetchSpec<Boolean> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(reader.isEnabled(UUID.randomUUID(), ReportKey.CREDITORS))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void isEnabled_contextVariant_shortCircuitsWithoutTenant() {
        StepVerifier.create(reader.isEnabled(ReportKey.CREDITORS))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void isEnabled_contextVariant_handlesInvalidTenantId() {
        StepVerifier.create(reader.isEnabled(ReportKey.CREDITORS)
                        .contextWrite(ctx -> TenantContext.put(reactor.util.context.Context.of(ctx), "not-a-uuid")))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void isEnabled_contextVariant_readsValidTenantAndCallsDb() {
        RowsFetchSpec<Boolean> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just(Boolean.FALSE));

        StepVerifier.create(reader.isEnabled(ReportKey.CREDITORS)
                        .contextWrite(ctx -> TenantContext.put(reactor.util.context.Context.of(ctx),
                                UUID.randomUUID().toString())))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void disabledKeys_mapsRowsToEnumValues_dropsUnknown() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.all()).thenReturn(Flux.just("CREDITORS", "BOGUS_KEY", "MEMBER_STATEMENT"));

        StepVerifier.create(reader.disabledKeys(UUID.randomUUID()))
                .assertNext(set -> assertThat(set).containsExactlyInAnyOrder(
                        ReportKey.CREDITORS, ReportKey.MEMBER_STATEMENT))
                .verifyComplete();
    }

    @Test
    void disabledKeys_nullTenantReturnsEmpty() {
        StepVerifier.create(reader.disabledKeys(null))
                .assertNext(set -> assertThat(set).isEmpty())
                .verifyComplete();
    }

    @Test
    void disabledKeys_swallowsDbErrorsAsEmpty() {
        RowsFetchSpec<String> fetch = stubFetch();
        when(fetch.all()).thenReturn(Flux.error(new RuntimeException("db down")));

        StepVerifier.create(reader.disabledKeys(UUID.randomUUID()))
                .assertNext(set -> assertThat(set).isEqualTo(Set.<ReportKey>of()))
                .verifyComplete();
    }
}
