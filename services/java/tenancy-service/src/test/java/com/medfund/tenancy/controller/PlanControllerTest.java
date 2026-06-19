package com.medfund.tenancy.controller;

import com.medfund.tenancy.config.SecurityConfig;
import com.medfund.tenancy.entity.Plan;
import com.medfund.tenancy.exception.GlobalExceptionHandler;
import com.medfund.tenancy.service.PlanService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(PlanController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PlanControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    PlanService planService;

    @Test
    void findAllActive_returns200() {
        when(planService.findAllActive()).thenReturn(Flux.just(createTestPlan()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/plans")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findById_returns200() {
        UUID id = UUID.randomUUID();
        Plan plan = createTestPlan();
        plan.setId(id);
        when(planService.findById(id)).thenReturn(Mono.just(plan));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/plans/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(planService.create(any(), any(), any())).thenReturn(Mono.just(createTestPlan()));

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/plans")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Basic\",\"maxMembers\":100,\"price\":99.99,\"currencyCode\":\"USD\",\"billingCycle\":\"monthly\"}")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    private Plan createTestPlan() {
        var p = new Plan();
        p.setId(UUID.randomUUID());
        p.setName("Basic");
        p.setPrice(BigDecimal.valueOf(99.99));
        p.setIsActive(true);
        return p;
    }
}
