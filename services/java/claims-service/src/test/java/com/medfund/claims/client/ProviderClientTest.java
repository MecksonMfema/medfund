package com.medfund.claims.client;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §C Phase 9 — ProviderClient batching + peer-down guard.
 *
 * <p>The peer-down path is the load-bearing behaviour: whether or not
 * user-service answers, the report envelope has to keep serving. This
 * test drives the client without a JWT in the reactive security context
 * (which triggers the immediate fallback path — the batch never leaves
 * the JVM) so it can run without a live upstream.
 *
 * <p>Fuller happy-path coverage would need MockWebServer which isn't on
 * the claims-service test classpath; the deviation is documented on the
 * plan's Phase 9 success criteria.
 */
class ProviderClientTest {

    @Test
    void batchLookup_emptyIds_returnsEmptyMap() {
        ProviderClient client = new ProviderClient("http://localhost:8082");
        StepVerifier.create(client.batchLookup(Set.of(), new ArrayList<>()))
                .assertNext(map -> assertThat(map).isEmpty())
                .verifyComplete();
    }

    @Test
    void batchLookup_noBearerToken_returnsPlaceholdersAndWarning() {
        ProviderClient client = new ProviderClient("http://localhost:8082");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<String> warnings = new ArrayList<>();
        StepVerifier.create(client.batchLookup(Set.of(a, b), warnings))
                .assertNext(map -> {
                    assertThat(map).hasSize(2);
                    assertThat(map.get(a).name()).isEqualTo("Provider Unknown");
                    assertThat(map.get(a).networkTier()).isEqualTo("STANDARD");
                    assertThat(map.get(b).name()).isEqualTo("Provider Unknown");
                })
                .verifyComplete();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("provider metadata unavailable for 2");
    }
}
