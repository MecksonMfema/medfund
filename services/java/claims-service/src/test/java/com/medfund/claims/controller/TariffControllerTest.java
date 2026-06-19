package com.medfund.claims.controller;

import com.medfund.claims.config.SecurityConfig;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.entity.TariffSchedule;
import com.medfund.claims.service.TariffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(TariffController.class)
@Import(SecurityConfig.class)
class TariffControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TariffService tariffService;

    @Test
    void findAllSchedules_returns200() {
        when(tariffService.findAllSchedules()).thenReturn(Flux.just(createTestSchedule()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tariffs/schedules")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findCodeByCode_returns200() {
        when(tariffService.findCodeByCode("CONS-001")).thenReturn(Mono.just(createTestCode()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tariffs/codes/{code}", "CONS-001")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void createSchedule_returns201() {
        when(tariffService.createSchedule(any(), any(), any())).thenReturn(Mono.just(createTestSchedule()));

        String body = """
                {
                    "name": "Standard Tariff 2026",
                    "effectiveDate": "2026-01-01",
                    "source": "AHFOZ"
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/tariffs/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private TariffSchedule createTestSchedule() {
        var s = new TariffSchedule();
        s.setId(UUID.randomUUID());
        s.setName("Standard Tariff 2026");
        s.setEffectiveDate(LocalDate.of(2026, 1, 1));
        s.setStatus("active");
        s.setCreatedAt(Instant.now());
        return s;
    }

    private TariffCode createTestCode() {
        var c = new TariffCode();
        c.setId(UUID.randomUUID());
        c.setCode("CONS-001");
        c.setDescription("General consultation");
        c.setUnitPrice(new BigDecimal("150.00"));
        c.setCurrencyCode("USD");
        c.setCreatedAt(Instant.now());
        return c;
    }
}
