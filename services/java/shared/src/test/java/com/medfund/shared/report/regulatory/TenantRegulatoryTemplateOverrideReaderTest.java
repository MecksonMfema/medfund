package com.medfund.shared.report.regulatory;

import com.medfund.shared.report.regulatory.TenantRegulatoryTemplateOverrideReader.Overlay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRegulatoryTemplateOverrideReaderTest {

    @Mock
    private DatabaseClient db;
    @Mock
    private DatabaseClient.GenericExecuteSpec spec;

    private TenantRegulatoryTemplateOverrideReader reader;

    @BeforeEach
    void setUp() {
        reader = new TenantRegulatoryTemplateOverrideReader(db);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    @SuppressWarnings("unchecked")
    private RowsFetchSpec<Overlay> stubFetch() {
        RowsFetchSpec<Overlay> fetch = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(BiFunction.class))).thenReturn(fetch);
        return fetch;
    }

    @Test
    void findEffective_returnsRowWhenPresent() {
        RowsFetchSpec<Overlay> fetch = stubFetch();
        byte[] bytes = new byte[]{1, 2, 3};
        when(fetch.one()).thenReturn(Mono.just(new Overlay(bytes, "2024-06-01")));

        StepVerifier.create(reader.findEffective(UUID.randomUUID(), "ipec", "ipec-quarterly-return", LocalDate.of(2026, 1, 1)))
                .assertNext(o -> {
                    assertThat(o.xlsxBytes()).isEqualTo(bytes);
                    assertThat(o.versionLabel()).isEqualTo("2024-06-01");
                })
                .verifyComplete();
    }

    @Test
    void findEffective_emptyWhenNoRow() {
        RowsFetchSpec<Overlay> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.empty());

        StepVerifier.create(reader.findEffective(UUID.randomUUID(), "ipec", "x", LocalDate.now()))
                .verifyComplete();
    }

    @Test
    void findEffective_shortCircuitsOnNullOrBlankArgs() {
        StepVerifier.create(reader.findEffective(null, "ipec", "x", LocalDate.now())).verifyComplete();
        StepVerifier.create(reader.findEffective(UUID.randomUUID(), null, "x", LocalDate.now())).verifyComplete();
        StepVerifier.create(reader.findEffective(UUID.randomUUID(), " ", "x", LocalDate.now())).verifyComplete();
        StepVerifier.create(reader.findEffective(UUID.randomUUID(), "ipec", null, LocalDate.now())).verifyComplete();
        StepVerifier.create(reader.findEffective(UUID.randomUUID(), "ipec", "x", null)).verifyComplete();
    }

    @Test
    void findEffective_swallowsDbErrorsAsEmpty() {
        RowsFetchSpec<Overlay> fetch = stubFetch();
        when(fetch.one()).thenReturn(Mono.error(new RuntimeException("db down")));

        // Fail-open on template overrides — the caller falls through to the
        // bundled path rather than 500ing every regulator report at once.
        StepVerifier.create(reader.findEffective(UUID.randomUUID(), "ipec", "x", LocalDate.now()))
                .verifyComplete();
    }
}
