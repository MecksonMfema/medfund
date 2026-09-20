package com.medfund.user.repository;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.service.UserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Dedicated-container base for the two provider-membership repository ITs.
 *
 * <p>These ITs cannot ride the shared {@code AbstractPostgresIntegrationTest}
 * container: {@link ProviderTenantRepository} and
 * {@link ProviderInsuranceLineRepository} schema-qualify {@code public.} on
 * every statement, so their fixture has to own {@code public.tenants} and
 * {@code public.providers} — and {@code GroupNumberServiceIT}'s fixture
 * creates its own differently-shaped {@code tenants} with a bare
 * {@code CREATE TABLE} on that same container. Whichever ran second would
 * either fail to create the table or insert against the wrong shape.
 *
 * <p>Both subclasses point Flyway at the same location, so they share this
 * container and its single context without a checksum collision — the
 * failure mode {@code AbstractDedicatedPostgresIntegrationTest} warns about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/provider-membership-migration",
    "spring.flyway.baseline-on-migrate=true",
})
@Import(AbstractProviderMembershipIT.SecurityStub.class)
abstract class AbstractProviderMembershipIT {

    /**
     * Kafka collaborators are mocked at the bean level: no broker runs here and
     * the surface under test is the {@code public.} SQL seam.
     * {@code ProviderMembershipServiceIT} drives both, so they are declared on
     * the base rather than per subclass: identical context definition across
     * all three subclasses is what lets them share one container and one boot.
     */
    @MockBean protected AuditPublisher auditPublisher;
    @MockBean protected UserEventPublisher userEventPublisher;

    @BeforeEach
    void stubPublishers() {
        Mockito.when(auditPublisher.publish(Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(userEventPublisher.publishProviderTenantLinked(Mockito.any(), Mockito.any()))
               .thenReturn(Mono.empty());
        Mockito.when(userEventPublisher.publishProviderTenantUnlinked(Mockito.any(), Mockito.any()))
               .thenReturn(Mono.empty());
    }

    /**
     * SecurityConfig requires a {@link ReactiveJwtDecoder}, normally supplied
     * by the OAuth2 resource-server autoconfig fetching JWKS from Keycloak.
     * The IT doesn't run Keycloak, so a no-op decoder keeps the filter chain
     * wireable. No authenticated endpoint is exercised here.
     */
    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("medfund")
            .withUsername("medfund")
            .withPassword("medfund")
            .withCommand("postgres", "-c", "max_connections=300", "-c", "shared_buffers=64MB");

    static {
        // Started once per JVM, not per test class — the @Testcontainers
        // extension would stop it at the end of each class and invalidate the
        // cached Spring context both subclasses share.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
            "r2dbc:postgresql://%s:%d/%s",
            POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
