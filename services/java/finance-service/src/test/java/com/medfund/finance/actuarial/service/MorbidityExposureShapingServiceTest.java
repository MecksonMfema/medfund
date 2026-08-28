package com.medfund.finance.actuarial.service;

import com.medfund.finance.actuarial.service.MorbidityExposureShapingService.MorbidityShapeRequest;
import com.medfund.finance.actuarial.service.MorbidityExposureShapingService.MorbidityShapeResult;
import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.MorbidityExposureFeedRow;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MorbidityExposureShapingServiceTest {

    private static final UUID TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock UserServiceClient userServiceClient;
    @Mock DatabaseClient databaseClient;
    @Mock DatabaseClient.GenericExecuteSpec spec;

    MorbidityExposureShapingService service;

    @BeforeEach
    void setUp() {
        service = new MorbidityExposureShapingService(userServiceClient, databaseClient);
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
                when(r.get(eq("basis_name"), eq(String.class))).thenReturn((String) row.get("basis_name"));
                when(r.get(eq("morbidity_multiplier"), eq(BigDecimal.class)))
                        .thenReturn((BigDecimal) row.get("morbidity_multiplier"));
                return mapper.apply(r, mock(RowMetadata.class));
            }));
            return fetch;
        });
    }

    @Test
    void assemblesExposurePayloadFromFeedAndBasis() {
        when(userServiceClient.morbidityIncidenceFeed(any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        new MorbidityExposureFeedRow("30-34", "male", 1000.0, 3),
                        new MorbidityExposureFeedRow("30-34", "female", 800.0, 1)
                )));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH",
                       "basis_name", "CIDA",
                       "morbidity_multiplier", new BigDecimal("1.1500"))
        ));

        MorbidityShapeResult result = service.shape(new MorbidityShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                "HEALTH", null, null
        )).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.exposure().get("cohorts");
        assertThat(cohorts).hasSize(1);
        Map<String, Object> cohort = cohorts.get(0);
        assertThat(cohort.get("insurance_line")).isEqualTo("HEALTH");
        assertThat(cohort.get("basis_name")).isEqualTo("CIDA");
        assertThat(cohort.get("multiplier")).isEqualTo(1.15);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bands = (List<Map<String, Object>>) cohort.get("bands");
        assertThat(bands).extracting(m -> m.get("age_band")).containsExactly("30-34", "30-34");
        assertThat(bands).extracting(m -> m.get("sex")).containsExactly("male", "female");
        assertThat(bands).extracting(m -> m.get("exposure_years")).containsExactly(1000.0, 800.0);
        assertThat(bands).extracting(m -> m.get("incidents")).containsExactly(3L, 1L);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void missingBasisFallsBackToDefaultWithWarning() {
        when(userServiceClient.morbidityIncidenceFeed(any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        new MorbidityExposureFeedRow("40-44", "male", 500.0, 2)
                )));
        stubBasisRows(List.of());  // empty basis

        MorbidityShapeResult result = service.shape(new MorbidityShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                "HEALTH", null, null
        )).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.exposure().get("cohorts");
        assertThat(cohorts).hasSize(1);
        Map<String, Object> cohort = cohorts.get(0);
        // Default basis kicks in.
        assertThat(cohort.get("basis_name")).isEqualTo("CIDA");
        assertThat(cohort.get("multiplier")).isEqualTo(1.0);
        assertThat(result.warnings()).anyMatch(w -> w.contains("no morbidity_basis"));
    }

    @Test
    void overrideBasisAndMultiplierPreferredOverTenantConfig() {
        when(userServiceClient.morbidityIncidenceFeed(any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        new MorbidityExposureFeedRow("30-34", "male", 1000.0, 3)
                )));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH",
                       "basis_name", "CIDA",
                       "morbidity_multiplier", new BigDecimal("1.0000"))
        ));

        MorbidityShapeResult result = service.shape(new MorbidityShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                "HEALTH", "GLTD87", 1.25
        )).block();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.exposure().get("cohorts");
        Map<String, Object> cohort = cohorts.get(0);
        assertThat(cohort.get("basis_name")).isEqualTo("GLTD87");
        assertThat(cohort.get("multiplier")).isEqualTo(1.25);
    }

    @Test
    void emptyFeedProducesEmptyBandsWithWarning() {
        when(userServiceClient.morbidityIncidenceFeed(any(), any(), any()))
                .thenReturn(Mono.just(List.of()));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH",
                       "basis_name", "CIDA",
                       "morbidity_multiplier", new BigDecimal("1.0000"))
        ));

        MorbidityShapeResult result = service.shape(new MorbidityShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30),
                "HEALTH", null, null
        )).block();

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.exposure().get("cohorts");
        assertThat(cohorts).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bands = (List<Map<String, Object>>) cohorts.get(0).get("bands");
        assertThat(bands).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("No member exposure"));
    }

    @Test
    void nullLineDefaultsToHealth() {
        when(userServiceClient.morbidityIncidenceFeed(any(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        new MorbidityExposureFeedRow("30-34", "male", 100.0, 1)
                )));
        stubBasisRows(List.of(
                Map.of("insurance_line", "HEALTH",
                       "basis_name", "CIDA",
                       "morbidity_multiplier", new BigDecimal("1.0000"))
        ));

        MorbidityShapeResult result = service.shape(new MorbidityShapeRequest(
                TENANT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                null, null, null
        )).block();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) result.exposure().get("cohorts");
        assertThat(cohorts.get(0).get("insurance_line")).isEqualTo("HEALTH");
    }

    @Test
    void invertedPeriodRejected() {
        try {
            new MorbidityShapeRequest(
                    TENANT, LocalDate.of(2024, 6, 30), LocalDate.of(2024, 1, 1),
                    "HEALTH", null, null
            );
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("<= periodEnd");
        }
    }
}
