package com.medfund.finance.regulatory.naic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class R2dbcUsTenantNaicConfigReaderTest {

    @Mock
    private DatabaseClient db;
    @Mock
    private DatabaseClient.GenericExecuteSpec spec;

    private R2dbcUsTenantNaicConfigReader reader;

    @BeforeEach
    void setUp() {
        reader = new R2dbcUsTenantNaicConfigReader(db);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    @SuppressWarnings("unchecked")
    private FetchSpec<Map<String, Object>> stubFetch() {
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        lenient().when(spec.fetch()).thenReturn(fetch);
        return fetch;
    }

    @Test
    void hasEffectiveConfig_returnsTrue_whenRowExists() {
        FetchSpec<Map<String, Object>> fetch = stubFetch();
        when(fetch.first()).thenReturn(Mono.just(Map.of("?column?", (Object) 1)));

        StepVerifier.create(reader.hasEffectiveConfig(UUID.randomUUID()))
                .assertNext(present -> assertThat(present).isTrue())
                .verifyComplete();
    }

    @Test
    void hasEffectiveConfig_returnsFalse_whenNoRow() {
        FetchSpec<Map<String, Object>> fetch = stubFetch();
        when(fetch.first()).thenReturn(Mono.empty());

        StepVerifier.create(reader.hasEffectiveConfig(UUID.randomUUID()))
                .assertNext(present -> assertThat(present).isFalse())
                .verifyComplete();
    }

    @Test
    void hasEffectiveConfig_returnsFalse_whenTenantIdIsNull() {
        StepVerifier.create(reader.hasEffectiveConfig(null))
                .assertNext(present -> assertThat(present).isFalse())
                .verifyComplete();
    }

    @Test
    void hasEffectiveConfig_failsClosed_onDbError() {
        FetchSpec<Map<String, Object>> fetch = stubFetch();
        when(fetch.first()).thenReturn(Mono.error(new RuntimeException("db down")));

        // Fail-closed matches the SPI contract: a database blip resolves to
        // false so the caller (a Phase 12/13 NAIC job service) 422s the
        // submission rather than letting an ungoverned filing through.
        StepVerifier.create(reader.hasEffectiveConfig(UUID.randomUUID()))
                .assertNext(present -> assertThat(present).isFalse())
                .verifyComplete();
    }
}
