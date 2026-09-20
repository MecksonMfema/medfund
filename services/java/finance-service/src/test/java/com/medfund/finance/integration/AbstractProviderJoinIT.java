package com.medfund.finance.integration;

import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared base for the five finance provider-join ITs.
 *
 * <p>Providers are platform-scoped ({@code public.providers}) and a tenant
 * only sees the ones it holds a {@code public.provider_tenants} row for, so
 * every finance query repository that surfaces a provider name now carries a
 * membership guard. These ITs pin that behaviour on the query repositories
 * that had no provider-join coverage at all before: seed one linked provider
 * and one unlinked one, and assert the name resolves for the first and comes
 * back blank for the second.
 *
 * <p>The tests drive the repositories directly rather than over HTTP. The
 * guard lives entirely in the SQL, the HTTP surfaces around these repos are
 * already covered elsewhere, and two of the five (notes, advance payments)
 * reach their provider join through list endpoints whose envelope machinery
 * adds nothing to what is being asserted here.
 *
 * <p>Subclasses share this configuration, so they also share one cached
 * Spring context and the JVM-wide container from
 * {@link AbstractIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(AbstractProviderJoinIT.SecurityStub.class)
abstract class AbstractProviderJoinIT extends AbstractIntegrationTest {

    /**
     * SecurityConfig needs a {@link ReactiveJwtDecoder} to wire its filter
     * chain; no authenticated endpoint is exercised by these repository-level
     * tests.
     */
    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test",
                        "realm_access", Map.of("roles", List.of("super_admin")))));
        }
    }

    /**
     * Repeated as {@code @WithTenant} on every subclass: the annotation is not
     * {@code @Inherited}, so {@code TenantContextExtension} cannot find it here.
     */
    static final String TENANT = "11111111-1111-1111-1111-111111111111";

    /** Contracted with {@link #TENANT}: its name resolves through the join. */
    protected static final UUID LINKED_PROVIDER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    /** In public.providers, but with no membership row for this tenant. */
    protected static final UUID UNLINKED_PROVIDER =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    protected static final String LINKED_NAME   = "Sunrise Clinic";
    protected static final String UNLINKED_NAME = "Offnetwork Hospital";
    protected static final String LINKED_REG    = "REG-1001";
    protected static final String UNLINKED_REG  = "REG-2002";

    @Autowired protected DatabaseClient db;

    /**
     * Wipes and reseeds the two providers. Subclasses call this from their own
     * {@code @BeforeEach} after clearing whatever table they drive from, so
     * the delete order stays correct (membership rows before providers).
     */
    protected void seedProviders() {
        run("DELETE FROM provider_tenants");
        run("DELETE FROM providers");

        insert("INSERT INTO providers (id, name, registration_number, email) "
                        + "VALUES (:id, :name, :reg, :email)",
                Map.of("id", LINKED_PROVIDER, "name", LINKED_NAME,
                        "reg", LINKED_REG, "email", "billing@sunrise.test"));
        insert("INSERT INTO providers (id, name, registration_number, email) "
                        + "VALUES (:id, :name, :reg, :email)",
                Map.of("id", UNLINKED_PROVIDER, "name", UNLINKED_NAME,
                        "reg", UNLINKED_REG, "email", "billing@offnetwork.test"));

        insert("INSERT INTO provider_tenants (provider_id, tenant_id, status, network_tier, in_network) "
                        + "VALUES (:pid, :tid, 'active', 'STANDARD', TRUE)",
                Map.of("pid", LINKED_PROVIDER, "tid", UUID.fromString(TENANT)));
    }

    protected void insert(String sql, Map<String, Object> params) {
        var spec = db.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        spec.fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    protected void run(String sql) {
        db.sql(sql).fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    /** Runs a repository call under the IT's tenant context. */
    protected <T> T inTenant(Mono<T> mono) {
        return mono.contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(10));
    }

    /** Runs a repository call under the IT's tenant context. */
    protected <T> List<T> inTenant(Flux<T> flux) {
        return flux.contextWrite(TenantTestContext.put())
                .collectList().block(Duration.ofSeconds(10));
    }
}
