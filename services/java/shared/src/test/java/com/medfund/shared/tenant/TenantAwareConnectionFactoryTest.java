package com.medfund.shared.tenant;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantAwareConnectionFactoryTest {

    @Mock
    private ConnectionFactory delegate;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Test
    void create_withTenantInContext_setsSearchPath() {
        doReturn(Mono.just(connection)).when(delegate).create();
        when(connection.createStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(Flux.empty());

        TenantAwareConnectionFactory factory = new TenantAwareConnectionFactory(delegate);

        Mono.from(factory.create())
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
                .block();

        verify(connection).createStatement(contains("tenant_testtenant"));
    }

    @Test
    void create_withoutTenant_returnsConnectionUnchanged() {
        // When no tenant is bound, the factory resets search_path to public
        // (instead of leaving stale tenant search_path on a pooled connection).
        // The connection itself is otherwise returned unchanged.
        doReturn(Mono.just(connection)).when(delegate).create();
        when(connection.createStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(Flux.empty());

        TenantAwareConnectionFactory factory = new TenantAwareConnectionFactory(delegate);

        Mono.from(factory.create())
                .block();

        // Exactly one statement — the public-schema reset — and never a
        // tenant_* schema switch.
        verify(connection).createStatement("SET search_path TO public");
        verify(connection, never()).createStatement(contains("tenant_"));
    }
}
