package com.medfund.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the tenant scoping of published security events. The prior
 * implementation stamped {@code tenantId = event.getRealmId()} (a
 * Keycloak-internal realm UUID), which in a shared-realm deployment
 * cross-tenant-leaked every LOGIN. These tests pin the two branches of the
 * new user-attribute resolver: attribute present → passes through;
 * attribute missing or user unresolvable → empty string.
 */
class MedfundEventListenerProviderTest {

    private static final String REALM_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String USER_ID  = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private KeycloakSession session;
    private RealmProvider realmProvider;
    private UserProvider userProvider;
    private RealmModel realm;
    private SecurityEventPublisher publisher;
    private MedfundEventListenerProvider provider;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        realmProvider = mock(RealmProvider.class);
        userProvider = mock(UserProvider.class);
        realm = mock(RealmModel.class);
        publisher = mock(SecurityEventPublisher.class);

        when(session.realms()).thenReturn(realmProvider);
        when(session.users()).thenReturn(userProvider);
        when(realmProvider.getRealm(REALM_ID)).thenReturn(realm);

        provider = new MedfundEventListenerProvider(session, publisher);
    }

    @Test
    void loginWithTenantAttribute_stampsThatTenantId() {
        String tenantUuid = "11111111-2222-3333-4444-555555555555";
        UserModel user = mock(UserModel.class);
        when(userProvider.getUserById(eq(realm), eq(USER_ID))).thenReturn(user);
        when(user.getFirstAttribute(MedfundEventListenerProvider.TENANT_ID_ATTRIBUTE))
                .thenReturn(tenantUuid);

        provider.onEvent(loginEvent(REALM_ID, USER_ID));

        assertEquals(tenantUuid, capturedMessage().tenantId());
    }

    @Test
    void loginWithoutTenantAttribute_stampsEmpty_notRealmId() {
        // Super-admin case: the user exists in Keycloak but has no tenant_id
        // attribute. The previous bug returned event.getRealmId() here.
        UserModel user = mock(UserModel.class);
        when(userProvider.getUserById(eq(realm), eq(USER_ID))).thenReturn(user);
        when(user.getFirstAttribute(MedfundEventListenerProvider.TENANT_ID_ATTRIBUTE))
                .thenReturn(null);

        provider.onEvent(loginEvent(REALM_ID, USER_ID));

        String stamped = capturedMessage().tenantId();
        assertEquals("", stamped, "empty tenantId keeps super-admin logins out of tenant-scoped queries");
    }

    @Test
    void loginErrorBeforeUserIdentified_stampsEmpty() {
        // A LOGIN_ERROR fired before Keycloak identifies the user carries
        // userId = null. The resolver must not attempt a lookup and must
        // stamp an empty tenantId so the row is platform-only.
        provider.onEvent(loginEvent(REALM_ID, null));

        assertEquals("", capturedMessage().tenantId());
    }

    @Test
    void loginWithUnknownUser_stampsEmpty() {
        // Defensive: the user id on the event doesn't resolve. Must not
        // leak the realm id as tenant id.
        when(userProvider.getUserById(eq(realm), eq(USER_ID))).thenReturn(null);

        provider.onEvent(loginEvent(REALM_ID, USER_ID));

        assertEquals("", capturedMessage().tenantId());
    }

    private static Event loginEvent(String realmId, String userId) {
        Event e = new Event();
        e.setType(EventType.LOGIN);
        e.setRealmId(realmId);
        e.setUserId(userId);
        e.setIpAddress("127.0.0.1");
        e.setTime(0L);
        e.setDetails(Map.of("username", "someone@example.com"));
        return e;
    }

    private SecurityEventMessage capturedMessage() {
        ArgumentCaptor<SecurityEventMessage> captor = ArgumentCaptor.forClass(SecurityEventMessage.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }
}
