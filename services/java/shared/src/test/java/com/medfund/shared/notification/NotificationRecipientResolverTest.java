package com.medfund.shared.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The resolver's guard clauses matter — the SQL string is a static
 * template, so all our defensive logic against bogus inputs lives at
 * the top of {@code forPermissions}. If we ever remove those checks
 * the join runs against every user, which would loudly spam the bell.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRecipientResolverTest {

    @Mock
    private DatabaseClient db;

    private NotificationRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NotificationRecipientResolver(db);
    }

    @Test
    void forPermissions_nullTenant_emitsEmptyWithoutHittingDb() {
        StepVerifier.create(resolver.forPermissions(null, List.of("billing:view")))
                .verifyComplete();
        verify(db, never()).sql(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forPermissions_blankTenant_emitsEmpty() {
        StepVerifier.create(resolver.forPermissions("   ", List.of("billing:view")))
                .verifyComplete();
        verify(db, never()).sql(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forPermissions_nullPermissions_emitsEmpty() {
        StepVerifier.create(resolver.forPermissions("tenant-a", null))
                .verifyComplete();
        verify(db, never()).sql(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forPermissions_emptyPermissions_emitsEmpty() {
        StepVerifier.create(resolver.forPermissions("tenant-a", List.of()))
                .verifyComplete();
        verify(db, never()).sql(org.mockito.ArgumentMatchers.anyString());
    }
}
