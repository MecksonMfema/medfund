package com.medfund.finance.security;

import com.medfund.shared.security.M2MTokenProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class M2MTokenProviderTest {

    private MockWebServer server;
    private M2MTokenProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new M2MTokenProvider(
                WebClient.builder(),
                server.url("/token").toString(),
                "svc-finance",
                "supersecret");
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void firstCall_hitsKeycloak_returnsAccessToken() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-1\",\"expires_in\":600}")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.getServiceToken("scheduled_report:render"))
                .expectNext("tok-1")
                .verifyComplete();

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/token");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("grant_type=client_credentials");
        assertThat(body).contains("scope=scheduled_report:render");
        assertThat(body).contains("client_id=svc-finance");
        assertThat(body).contains("client_secret=supersecret");
    }

    @Test
    void secondCall_hitsCache_noSecondKeycloakRequest() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-1\",\"expires_in\":600}")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.getServiceToken("scoped")).expectNext("tok-1").verifyComplete();
        StepVerifier.create(provider.getServiceToken("scoped")).expectNext("tok-1").verifyComplete();

        // Only the first request should have hit Keycloak.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void differentScopes_haveIndependentCacheEntries() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-A\",\"expires_in\":600}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-B\",\"expires_in\":600}")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.getServiceToken("scope-A")).expectNext("tok-A").verifyComplete();
        StepVerifier.create(provider.getServiceToken("scope-B")).expectNext("tok-B").verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void tokenNearExpiry_isRefetched() throws Exception {
        // Second reply returns a rotated token — cache invalidation on <30s
        // life is implicit because expires_in-30 = 0 seconds.
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-1\",\"expires_in\":29}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"tok-2\",\"expires_in\":600}")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(provider.getServiceToken("scoped"))
                .expectNext("tok-1").verifyComplete();

        // Sleep a hair so the expiry-30 window closes.
        Thread.sleep(50);
        StepVerifier.create(provider.getServiceToken("scoped"))
                .expectNext("tok-2").verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
