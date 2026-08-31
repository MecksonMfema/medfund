package com.medfund.shared.tenant;

import com.medfund.shared.tenant.TenantMetadataReader.TenantMetadata;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMetadataReaderTest {

    @Mock
    private DatabaseClient db;
    @Mock
    private DatabaseClient.GenericExecuteSpec spec;

    private TenantMetadataReader reader;

    @BeforeEach
    void setUp() {
        reader = new TenantMetadataReader(db);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    @SuppressWarnings("unchecked")
    private RowsFetchSpec<TenantMetadata> stubFetch() {
        RowsFetchSpec<TenantMetadata> fetch = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(BiFunction.class))).thenReturn(fetch);
        return fetch;
    }

    @Test
    void empty_hasNullFieldsAndIsReusable() {
        assertThat(TenantMetadata.empty().jurisdictionCode()).isNull();
        assertThat(TenantMetadata.empty().countryCode()).isNull();
    }

    @Test
    void load_returnsRowWhenPresent() {
        RowsFetchSpec<TenantMetadata> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just(new TenantMetadata("ZW_IPEC_SHORT_TERM", "ZW")));

        StepVerifier.create(reader.load(UUID.randomUUID()))
                .assertNext(m -> {
                    assertThat(m.jurisdictionCode()).isEqualTo("ZW_IPEC_SHORT_TERM");
                    assertThat(m.countryCode()).isEqualTo("ZW");
                })
                .verifyComplete();
    }

    @Test
    void load_defaultsToEmptyWhenNoRow() {
        RowsFetchSpec<TenantMetadata> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.empty());

        StepVerifier.create(reader.load(UUID.randomUUID()))
                .assertNext(m -> assertThat(m).isEqualTo(TenantMetadata.empty()))
                .verifyComplete();
    }

    @Test
    void load_nullTenantReturnsEmpty() {
        StepVerifier.create(reader.load(null))
                .assertNext(m -> assertThat(m).isEqualTo(TenantMetadata.empty()))
                .verifyComplete();
    }

    @Test
    void load_swallowsDbErrorsAsEmpty() {
        RowsFetchSpec<TenantMetadata> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.error(new RuntimeException("db down")));

        // Failing closed on DB errors is the deliberate design — the caller
        // (a guard aspect) then denies because null metadata can't match any
        // allowed value. Keeps the security gate honest under partial outage.
        StepVerifier.create(reader.load(UUID.randomUUID()))
                .assertNext(m -> assertThat(m).isEqualTo(TenantMetadata.empty()))
                .verifyComplete();
    }

    @Test
    void loadFromContext_shortCircuitsWhenNoTenantInContext() {
        StepVerifier.create(reader.loadFromContext())
                .assertNext(m -> assertThat(m).isEqualTo(TenantMetadata.empty()))
                .verifyComplete();
    }

    @Test
    void loadFromContext_handlesInvalidTenantId() {
        StepVerifier.create(reader.loadFromContext()
                        .contextWrite(ctx -> TenantContext.put(reactor.util.context.Context.of(ctx), "not-a-uuid")))
                .assertNext(m -> assertThat(m).isEqualTo(TenantMetadata.empty()))
                .verifyComplete();
    }

    @Test
    void loadFromContext_readsValidTenantAndCallsDb() {
        RowsFetchSpec<TenantMetadata> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.just(new TenantMetadata("US_NAIC", "US")));

        StepVerifier.create(reader.loadFromContext()
                        .contextWrite(ctx -> TenantContext.put(reactor.util.context.Context.of(ctx),
                                UUID.randomUUID().toString())))
                .assertNext(m -> {
                    assertThat(m.jurisdictionCode()).isEqualTo("US_NAIC");
                    assertThat(m.countryCode()).isEqualTo("US");
                })
                .verifyComplete();
    }
}
