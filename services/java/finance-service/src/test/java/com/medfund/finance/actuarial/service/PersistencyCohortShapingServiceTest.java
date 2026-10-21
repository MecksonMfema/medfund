package com.medfund.finance.actuarial.service;

import com.medfund.finance.actuarial.service.PersistencyCohortShapingService.PersistencyShapeRequest;
import com.medfund.finance.actuarial.service.PersistencyCohortShapingService.PersistencyShapeResult;
import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.PersistencyCohortFeedRow;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistencyCohortShapingServiceTest {

    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock UserServiceClient userServiceClient;
    @Mock DatabaseClient databaseClient;
    @Mock DatabaseClient.GenericExecuteSpec spec;

    PersistencyCohortShapingService service;

    @BeforeEach
    void setUp() {
        service = new PersistencyCohortShapingService(userServiceClient, databaseClient);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
    }

    @SuppressWarnings("unchecked")
    private void stubBasisRows(List<Map<String, Object>> rows) {
        RowsFetchSpec<Object> fetch = mock(RowsFetchSpec.class);
        lenient().when(spec.map(any(BiFunction.class))).thenAnswer(inv -> {
            BiFunction<Row, RowMetadata, Object> mapper = inv.getArgument(0);
            when(fetch.all()).thenAnswer(i -> Flux.fromIterable(rows).map(row -> {
                Row r = mock(Row.class);
                when(r.get(eq("insurance_line"), eq(String.class))).thenReturn((String) row.get("insurance_line"));
                when(r.get(eq("cohort_months"), eq(Integer.class))).thenReturn((Integer) row.get("cohort_months"));
                when(r.get(eq("expected_retention_pct"), eq(BigDecimal.class)))
                        .thenReturn((BigDecimal) row.get("expected_retention_pct"));
                return mapper.apply(r, mock(RowMetadata.class));
            }));
            return fetch;
        });
    }

    @Test
    void assemblesCohortPayloadFromFeedAndBasis() {
        when(userServiceClient.persistencyCohortFeed(any(), any(), anyList(), any()))
                .thenReturn(Mono.just(List.of(
                        new PersistencyCohortFeedRow(LocalDate.of(2024, 1, 1), "HEALTH", 3,
                                100L, 90L, new BigDecimal("90.00")),
                        new PersistencyCohortFeedRow(LocalDate.of(2024, 1, 1), "HEALTH", 6,
                                100L, 82L, new BigDecimal("82.00")),
                        new PersistencyCohortFeedRow(LocalDate.of(2024, 2, 1), "HEALTH", 3,
                                80L, 76L, new BigDecimal("95.00"))
                )));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH", "cohort_months", 3,
                       "expected_retention_pct", new BigDecimal("0.9000")),
                Map.of("insurance_line", "HEALTH", "cohort_months", 6,
                       "expected_retention_pct", new BigDecimal("0.8500"))
        ));

        PersistencyShapeResult result = service.shape(new PersistencyShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                List.of(3, 6, 12), "HEALTH"
        )).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.cohort().get("cohorts");
        assertThat(cohorts).hasSize(2);
        Map<String, Object> jan = cohorts.get(0);
        assertThat(jan.get("cohort_month")).isEqualTo("2024-01");
        assertThat(jan.get("insurance_line")).isEqualTo("HEALTH");
        assertThat(jan.get("cohort_size")).isEqualTo(100L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> janCps = (List<Map<String, Object>>) jan.get("checkpoints");
        assertThat(janCps).extracting(m -> m.get("months")).containsExactly(3, 6);
        assertThat(janCps).extracting(m -> m.get("retained_count")).containsExactly(90L, 82L);

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> basis =
                (Map<String, List<Map<String, Object>>>) result.cohort().get("expected_basis");
        assertThat(basis).containsOnlyKeys("HEALTH");
        assertThat(basis.get("HEALTH")).extracting(m -> m.get("cohort_months")).containsExactly(3, 6);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void emptyFeedProducesEmptyCohortsWithWarning() {
        when(userServiceClient.persistencyCohortFeed(any(), any(), anyList(), any()))
                .thenReturn(Mono.just(List.of()));
        stubBasisRows(List.of(Map.of(
                "insurance_line", "HEALTH", "cohort_months", 3,
                "expected_retention_pct", new BigDecimal("0.9000")
        )));

        PersistencyShapeResult result = service.shape(new PersistencyShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30),
                List.of(3), "HEALTH"
        )).block();

        assertThat(result).isNotNull();
        assertThat((List<?>) result.cohort().get("cohorts")).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("No cohorts"));
    }

    @Test
    void invertedPeriodRejected() {
        try {
            new PersistencyShapeRequest(
                    TENANT, LocalDate.of(2024, 6, 30), LocalDate.of(2024, 1, 1),
                    List.of(3), "HEALTH"
            );
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("<= periodEnd");
        }
    }
}
