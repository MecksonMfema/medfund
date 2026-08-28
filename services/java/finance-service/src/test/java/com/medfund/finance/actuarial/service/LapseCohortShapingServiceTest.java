package com.medfund.finance.actuarial.service;

import com.medfund.finance.actuarial.service.LapseCohortShapingService.LapseShapeRequest;
import com.medfund.finance.actuarial.service.LapseCohortShapingService.LapseShapeResult;
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
class LapseCohortShapingServiceTest {

    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock UserServiceClient userServiceClient;
    @Mock DatabaseClient databaseClient;
    @Mock DatabaseClient.GenericExecuteSpec spec;

    LapseCohortShapingService service;

    @BeforeEach
    void setUp() {
        service = new LapseCohortShapingService(userServiceClient, databaseClient);
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
    void assemblesCohortPayloadFromFeedAndBasisWithStillActiveKey() {
        // Same feed rows the persistency service reads; the shape service
        // relabels stillActive → still_active in the payload so the Python
        // lapse compute can derive lapsed_count.
        when(userServiceClient.persistencyCohortFeed(any(), any(), anyList(), any()))
                .thenReturn(Mono.just(List.of(
                        new PersistencyCohortFeedRow(LocalDate.of(2024, 1, 1), "HEALTH", 3,
                                100L, 90L, new BigDecimal("90.00")),
                        new PersistencyCohortFeedRow(LocalDate.of(2024, 1, 1), "HEALTH", 6,
                                100L, 82L, new BigDecimal("82.00"))
                )));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH", "cohort_months", 3,
                       "expected_retention_pct", new BigDecimal("0.9000")),
                Map.of("insurance_line", "HEALTH", "cohort_months", 6,
                       "expected_retention_pct", new BigDecimal("0.8500"))
        ));

        LapseShapeResult result = service.shape(new LapseShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                List.of(3, 6), "HEALTH"
        )).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.cohort().get("cohorts");
        assertThat(cohorts).hasSize(1);
        Map<String, Object> jan = cohorts.get(0);
        assertThat(jan.get("cohort_month")).isEqualTo("2024-01");
        assertThat(jan.get("cohort_size")).isEqualTo(100L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cps = (List<Map<String, Object>>) jan.get("checkpoints");
        assertThat(cps).extracting(m -> m.get("months")).containsExactly(3, 6);
        // The lapse payload names the count "still_active" rather than
        // persistency's "retained_count" so the Python compute can derive
        // lapsed = cohort_size − still_active without renaming twice.
        assertThat(cps).extracting(m -> m.get("still_active")).containsExactly(90L, 82L);

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> basis =
                (Map<String, List<Map<String, Object>>>) result.cohort().get("expected_basis");
        assertThat(basis).containsOnlyKeys("HEALTH");
        assertThat(basis.get("HEALTH")).extracting(m -> m.get("cohort_months")).containsExactly(3, 6);
        // The Python side derives expected_lapse = 1 - expected_retention_pct
        // so the shape service ships retention as-is.
        assertThat(basis.get("HEALTH")).extracting(m -> m.get("expected_retention_pct"))
                .containsExactly(0.9d, 0.85d);
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

        LapseShapeResult result = service.shape(new LapseShapeRequest(
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
            new LapseShapeRequest(
                    TENANT, LocalDate.of(2024, 6, 30), LocalDate.of(2024, 1, 1),
                    List.of(3), "HEALTH"
            );
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("<= periodEnd");
        }
    }
}
