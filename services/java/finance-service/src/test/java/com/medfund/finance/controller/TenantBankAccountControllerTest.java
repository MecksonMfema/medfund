package com.medfund.finance.controller;

import com.medfund.finance.config.SecurityConfig;
import com.medfund.finance.entity.TenantBankAccount;
import com.medfund.finance.service.TenantBankAccountService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(TenantBankAccountController.class)
@Import(SecurityConfig.class)
class TenantBankAccountControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TenantBankAccountService service;

    @Test
    void list_returns200() {
        when(service.findAll()).thenReturn(Flux.just(sampleAccount()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenant-bank-accounts")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void listByCurrency_returns200() {
        when(service.findByCurrency("USD")).thenReturn(Flux.just(sampleAccount()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenant-bank-accounts?currency=USD")
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void get_returns200() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Mono.just(sampleAccount()));

        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/tenant-bank-accounts/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void create_returns201() {
        when(service.create(any(), any(), any())).thenReturn(Mono.just(sampleAccount()));

        String body = """
                {
                    "bankName": "Standard Chartered",
                    "accountNumber": "0100123456",
                    "accountName": "MedFund Operations",
                    "currencyCode": "USD",
                    "label": "Ops USD",
                    "notes": "Primary payout account",
                    "nominated": true,
                    "active": true
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .post().uri("/api/v1/tenant-bank-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void update_returns200() {
        UUID id = UUID.randomUUID();
        when(service.update(any(), any(), any(), any())).thenReturn(Mono.just(sampleAccount()));

        String body = """
                {
                    "bankName": "Standard Chartered",
                    "accountNumber": "0100123456",
                    "accountName": "MedFund Operations",
                    "currencyCode": "USD",
                    "label": "Ops USD (renamed)",
                    "nominated": false,
                    "active": true
                }
                """;

        webTestClient.mutateWith(mockJwt())
                .put().uri("/api/v1/tenant-bank-accounts/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void delete_returns204() {
        UUID id = UUID.randomUUID();
        when(service.delete(any(), any(), any())).thenReturn(Mono.empty());

        webTestClient.mutateWith(mockJwt())
                .delete().uri("/api/v1/tenant-bank-accounts/{id}", id)
                .header("X-Tenant-ID", "test-tenant")
                .exchange()
                .expectStatus().isNoContent();
    }

    /**
     * The permission aspect is enforced end-to-end when {@code PermissionsAutoConfiguration}
     * loads (production boot). {@code @WebFluxTest} slices skip auto-configuration so the
     * aspect no-ops in the slice — a reflection check keeps the wiring honest: every
     * mutating verb must carry the {@code admin.bank_accounts:manage} guard.
     */
    @Test
    void mutatingMethodsAreGuardedByRequiresPermission() throws Exception {
        assertGuarded("create", UpsertTenantBankAccountRequest_class());
        assertGuarded("update", UUID.class, UpsertTenantBankAccountRequest_class());
        assertGuarded("delete", UUID.class);
    }

    private void assertGuarded(String methodName, Class<?>... firstParams) throws Exception {
        Method[] methods = TenantBankAccountController.class.getDeclaredMethods();
        Method match = null;
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean matches = pt.length >= firstParams.length;
            for (int i = 0; matches && i < firstParams.length; i++) {
                if (!pt[i].equals(firstParams[i])) matches = false;
            }
            if (matches) { match = m; break; }
        }
        assertThat(match).as("controller method %s", methodName).isNotNull();
        RequiresPermission ann = match.getAnnotation(RequiresPermission.class);
        assertThat(ann).as("@RequiresPermission on %s", methodName).isNotNull();
        assertThat(ann.value()).containsExactly(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE);
    }

    private static Class<?> UpsertTenantBankAccountRequest_class() throws ClassNotFoundException {
        return Class.forName("com.medfund.finance.dto.UpsertTenantBankAccountRequest");
    }

    private TenantBankAccount sampleAccount() {
        var a = new TenantBankAccount();
        a.setId(UUID.randomUUID());
        a.setBankName("Standard Chartered");
        a.setAccountNumber("0100123456");
        a.setAccountName("MedFund Operations");
        a.setCurrencyCode("USD");
        a.setLabel("Ops USD");
        a.setNotes("Primary payout account");
        a.setNominated(true);
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }
}
