package com.medfund.contributions.client;

import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebClient-level tests using a custom ExchangeFunction so we don't need
 * MockWebServer. Each test records the outgoing ClientRequest so we can
 * assert URL, method, and headers against what the arrears executor
 * actually sends.
 *
 * <p>Body assertions are covered via the escalator's argument captor —
 * we don't re-verify JSON marshalling here to keep this focused on the
 * routing contract with user-service (URL + verb + tenant header).
 */
class UserServiceClientTest {

    private RecordingExchange exchange;
    private UserServiceClient client;

    @BeforeEach
    void setUp() {
        exchange = new RecordingExchange();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        client = new UserServiceClient(builder, "http://user-service:8082");
    }

    @Test
    void suspendMember_postsToActionsPath_withTenantHeader() {
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.suspendMember(id, null, "ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        ClientRequest req = exchange.lastRequest();
        assertThat(req.method()).isEqualTo(HttpMethod.POST);
        assertThat(req.url().getPath()).isEqualTo("/api/v1/members/" + id + "/actions/suspend");
        assertThat(req.headers().getFirst("X-Tenant-ID")).isEqualTo("tnt-1");
    }

    @Test
    void deactivateMember_hitsDeactivateVerb() {
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.deactivateMember(id, LocalDate.of(2026, 12, 1), "OFFBOARDING")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath())
                .isEqualTo("/api/v1/members/" + id + "/actions/deactivate");
    }

    @Test
    void reactivateMember_hitsActivateVerb() {
        // reactivate aliases to activate on the wire — asserted here so a
        // rename on either side surfaces at test time, not in production.
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.reactivateMember(id, "ARREARS_CLEARED")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath())
                .isEqualTo("/api/v1/members/" + id + "/actions/activate");
    }

    @Test
    void suspendGroup_hitsGroupsPath() {
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.suspendGroup(id, null, "ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath())
                .isEqualTo("/api/v1/groups/" + id + "/actions/suspend");
    }

    @Test
    void deactivateGroup_hitsGroupsPath() {
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.deactivateGroup(id, null, "ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath())
                .isEqualTo("/api/v1/groups/" + id + "/actions/deactivate");
    }

    @Test
    void reactivateGroup_hitsGroupsActivate() {
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, "");

        StepVerifier.create(client.reactivateGroup(id, "ARREARS_CLEARED")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath())
                .isEqualTo("/api/v1/groups/" + id + "/actions/activate");
    }

    @Test
    void listMembersSuspendedForReason_getsAndMapsIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String body = "[{\"id\":\"" + id1 + "\"},{\"id\":\"" + id2 + "\"}]";
        exchange.setResponse(HttpStatus.OK, body);

        StepVerifier.create(client.listMembersSuspendedForReason("ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .expectNext(id1, id2)
                .verifyComplete();

        assertThat(exchange.lastRequest().method()).isEqualTo(HttpMethod.GET);
        assertThat(exchange.lastRequest().url().getPath()).isEqualTo("/api/v1/members/suspended");
        assertThat(exchange.lastRequest().url().getQuery()).contains("reason=ARREARS_ESCALATION");
    }

    @Test
    void listGroupsSuspendedForReason_getsAndMapsIds() {
        UUID id1 = UUID.randomUUID();
        String body = "[{\"id\":\"" + id1 + "\"}]";
        exchange.setResponse(HttpStatus.OK, body);

        StepVerifier.create(client.listGroupsSuspendedForReason("ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .expectNext(id1)
                .verifyComplete();

        assertThat(exchange.lastRequest().url().getPath()).isEqualTo("/api/v1/groups/suspended");
    }

    @Test
    void nonSuccessResponse_swallowsToEmpty() {
        // Documented contract: transient/network errors log-and-swallow so
        // a single bad row doesn't block the arrears sweep. Verify no
        // exception propagates.
        UUID id = UUID.randomUUID();
        exchange.setResponse(HttpStatus.SERVICE_UNAVAILABLE, "");

        StepVerifier.create(client.suspendMember(id, null, "ARREARS_ESCALATION")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .verifyComplete();
    }

    /** Exchange function that records requests + returns a canned response. */
    static class RecordingExchange implements ExchangeFunction {
        private final List<ClientRequest> requests = new ArrayList<>();
        private HttpStatus responseStatus = HttpStatus.OK;
        private String responseBody = "";

        void setResponse(HttpStatus status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        ClientRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            return Mono.just(ClientResponse.create(responseStatus)
                    .header("Content-Type", "application/json")
                    .body(responseBody)
                    .build());
        }
    }
}
