package com.medfund.tenancy.service;

import com.medfund.tenancy.dto.MemberNumberConfigResponse;
import com.medfund.tenancy.dto.UpdateMemberNumberConfigRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards read-then-write semantics for the V126 config endpoint:
 *
 * <ul>
 *   <li>get() maps the projected columns back to the DTO with the V126
 *       defaults filling in any nulls.
 *   <li>update() rejects an unknown tenantId with a friendly
 *       IllegalArgumentException rather than a silent 0-row noop.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemberNumberConfigServiceTest {

    @Mock DatabaseClient db;

    private MemberNumberConfigService service;

    @BeforeEach
    void setUp() {
        service = new MemberNumberConfigService(db);
    }

    @Test
    void get_returnsRowMappedToDto() {
        UUID tenantId = UUID.randomUUID();
        MemberNumberConfigResponse row = new MemberNumberConfigResponse(
                tenantId, "SHARED_WITH_SUFFIX", "MED-", "DEP-", 8, "_", 4, 1);
        stubOne(row);

        StepVerifier.create(service.get(tenantId))
                .assertNext(cfg -> {
                    assertThat(cfg.tenantId()).isEqualTo(tenantId);
                    assertThat(cfg.memberNumberScheme()).isEqualTo("SHARED_WITH_SUFFIX");
                    assertThat(cfg.memberNumberPrefix()).isEqualTo("MED-");
                    assertThat(cfg.memberNumberRandomLength()).isEqualTo(8);
                    assertThat(cfg.memberNumberSuffixSeparator()).isEqualTo("_");
                    assertThat(cfg.memberNumberSuffixPadding()).isEqualTo(4);
                    assertThat(cfg.memberNumberSuffixStart()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void get_missingTenant_errors() {
        // No row found → switchIfEmpty must surface an IllegalArgumentException
        // so the controller can 404 rather than silently returning defaults.
        stubOneEmpty();

        StepVerifier.create(service.get(UUID.randomUUID()))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not found"))
                .verify();
    }

    @Test
    void update_zeroRows_errors() {
        // A PUT to a non-existent tenant returns 0 rows updated — the
        // service must surface that as an argument error rather than
        // pretending success.
        UUID tenantId = UUID.randomUUID();
        stubUpdate(0L);

        UpdateMemberNumberConfigRequest req = new UpdateMemberNumberConfigRequest(
                "INDEPENDENT", "MBR-", "DEP-", 6, "-", 2, 1);

        StepVerifier.create(service.update(tenantId, req))
                .expectErrorSatisfies(t -> assertThat(t)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not found"))
                .verify();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubOne(MemberNumberConfigResponse row) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<MemberNumberConfigResponse> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(row));
    }

    @SuppressWarnings("unchecked")
    private void stubOneEmpty() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<MemberNumberConfigResponse> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void stubUpdate(long rows) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mock(org.springframework.r2dbc.core.FetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetch);
        lenient().when(fetch.rowsUpdated()).thenReturn(Mono.just(rows));
    }
}
