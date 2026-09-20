package com.medfund.tenancy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.tenancy.TenancyServiceApplication;
import com.medfund.tenancy.dto.UpdatePlatformSettingsRequest;
import com.medfund.tenancy.entity.PlatformSettings;
import com.medfund.tenancy.repository.PlatformSettingsRepository;
import com.medfund.tenancy.service.PlatformSettingsService;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4: every platform-settings mutation patches the Keycloak platform
 * realm so the login screen matches what the super admin saved.
 *
 * <p>Keycloak itself is stubbed with MockWebServer rather than booted in a
 * container: the assertion that matters is the shape of the outgoing admin
 * REST call (path, method, payload), and the codebase already stubs peer HTTP
 * this way (see finance-service {@code CollectionRateTrendControllerIT}). A
 * real realm round-trip is covered by the plan's manual verification steps
 * against {@code make infra}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = TenancyServiceApplication.class)
@TestPropertySource(properties = {
        // Same isolated DDL folder as PlatformSettingsIT: platform_settings has
        // no dependency on the tables other ITs stack on db/test-migration.
        "spring.flyway.locations=classpath:db/platform-settings-it-migration",
        "spring.flyway.out-of-order=false",
        "keycloak.platform-realm=medfund-platform",
        "platform.public-base-url=https://portal.medfund.example"
})
@Import(KeycloakRealmSyncIT.SecurityStub.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakRealmSyncIT extends AbstractIntegrationTest {

    private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000002";
    private static final String ACTOR_EMAIL = "realm-admin@medfund.example";
    private static final String TOKEN_PATH = "/realms/master/protocol/openid-connect/token";
    private static final String REALM_PATH = "/admin/realms/medfund-platform";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MockWebServer KEYCLOAK = new MockWebServer();

    /** Status the stub returns for the realm PUT; flipped to prove graceful degradation. */
    private static final AtomicInteger REALM_PUT_STATUS = new AtomicInteger(204);

    static {
        KEYCLOAK.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith(TOKEN_PATH)) {
                    return new MockResponse()
                            .setBody("{\"access_token\":\"stub-admin-token\",\"expires_in\":60}")
                            .addHeader("Content-Type", "application/json");
                }
                if (path.startsWith(REALM_PATH)) {
                    return new MockResponse().setResponseCode(REALM_PUT_STATUS.get());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            KEYCLOAK.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start the Keycloak MockWebServer stub", e);
        }
    }

    @AfterAll
    static void shutdownStub() throws IOException {
        KEYCLOAK.shutdown();
    }

    @DynamicPropertySource
    static void keycloakUrl(DynamicPropertyRegistry registry) {
        // url("/") ends with a slash; the service concatenates absolute paths.
        registry.add("keycloak.admin.url",
                () -> KEYCLOAK.url("/").toString().replaceAll("/$", ""));
    }

    @Autowired
    private PlatformSettingsService settingsService;

    @Autowired
    private PlatformSettingsRepository repo;

    /**
     * Put the singleton row back the way V183 seeded it. The Postgres
     * container is shared by every IT in the JVM and
     * {@code PlatformSettingsIT.seedRowIsPresent} asserts the pristine seed,
     * so whichever class runs first must leave the row alone for the other.
     */
    @AfterAll
    void restoreSeedRow() {
        PlatformSettings row = repo.findFirstBySingletonIsTrue().block(Duration.ofSeconds(10));
        if (row == null) {
            return;
        }
        row.setPlatformName("MedFund");
        row.setSupportEmail(null);
        row.setThemeTemplateId("ocean");
        row.setDarkMode(Boolean.FALSE);
        row.setHeroTitle(null);
        row.setHeroSubtitle(null);
        row.setLogoBytes(null);
        row.setLogoMime(null);
        repo.save(row).block(Duration.ofSeconds(10));
    }

    /**
     * The stub's request queue is shared across the class, so leftovers from
     * an earlier test would satisfy the next one's {@link #awaitRealmPut()}.
     */
    @BeforeEach
    void drainStubQueue() throws InterruptedException {
        while (KEYCLOAK.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
    }

    @Test
    void updatePatchesRealmDisplayNameAndHeroHtml() throws Exception {
        REALM_PUT_STATUS.set(204);
        var req = new UpdatePlatformSettingsRequest(
                "MedFund Realm Sync",
                "realm-sync@medfund.example",
                "ocean",
                Boolean.FALSE,
                "Cover that travels with you",
                "Sign in to manage your claims."
        );
        PlatformSettings saved = settingsService.update(req, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        assertNotNull(saved);

        RecordedRequest put = awaitRealmPut();
        assertEquals("PUT", put.getMethod());
        assertEquals(REALM_PATH, put.getPath());
        assertEquals("Bearer stub-admin-token", put.getHeader("Authorization"));

        JsonNode body = MAPPER.readTree(put.getBody().readUtf8());
        assertEquals("MedFund Realm Sync", body.path("displayName").asText());
        String html = body.path("displayNameHtml").asText();
        assertTrue(html.contains("<h1>Cover that travels with you</h1>"),
                "hero title must render in displayNameHtml, got: " + html);
        assertTrue(html.contains("<p>Sign in to manage your claims.</p>"),
                "hero subtitle must render in displayNameHtml, got: " + html);
    }

    @Test
    void heroCopyIsHtmlEscaped() throws Exception {
        REALM_PUT_STATUS.set(204);
        var req = new UpdatePlatformSettingsRequest(
                null, null, null, null,
                "<script>alert(1)</script>",
                null
        );
        assertNotNull(settingsService.update(req, ACTOR_ID, ACTOR_EMAIL).block(Duration.ofSeconds(10)));

        JsonNode body = MAPPER.readTree(awaitRealmPut().getBody().readUtf8());
        String html = body.path("displayNameHtml").asText();
        // displayNameHtml is emitted raw by the Keycloak theme, so an operator
        // supplied hero must arrive escaped or it is stored XSS on the login page.
        assertFalse(html.contains("<script>"), "raw <script> must never reach Keycloak: " + html);
        assertTrue(html.contains("&lt;script&gt;"), "hero must be escaped, got: " + html);
    }

    @Test
    void keycloakFailureDoesNotFailTheSave() {
        REALM_PUT_STATUS.set(500);
        var req = new UpdatePlatformSettingsRequest(
                "MedFund Realm Offline", null, null, null, null, null);

        PlatformSettings saved = settingsService.update(req, ACTOR_ID, ACTOR_EMAIL)
                .block(Duration.ofSeconds(10));
        assertNotNull(saved, "a Keycloak 500 must not fail the admin's save");
        assertEquals("MedFund Realm Offline", saved.getPlatformName());

        PlatformSettings reread = settingsService.get().block(Duration.ofSeconds(10));
        assertNotNull(reread);
        assertEquals("MedFund Realm Offline", reread.getPlatformName(),
                "the row must still be committed when the realm sync fails");
        REALM_PUT_STATUS.set(204);
    }

    /**
     * Drains the stub's request queue until the realm PUT shows up. Each sync
     * issues two calls (admin token, then the realm patch) and the reactive
     * chain completes before the stub has necessarily recorded both.
     */
    private RecordedRequest awaitRealmPut() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            RecordedRequest rec = KEYCLOAK.takeRequest(5, TimeUnit.SECONDS);
            if (rec == null) {
                break;
            }
            if ("PUT".equals(rec.getMethod())) {
                return rec;
            }
        }
        throw new AssertionError("no PUT " + REALM_PATH + " reached the Keycloak stub");
    }

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", ACTOR_ID)
                    .claim("email", ACTOR_EMAIL)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
        }
    }
}
