package com.medfund.shared.tenant;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantAwareConnectionFactoryTest {

    @Mock private ConnectionFactory delegate;
    @Mock private Connection         connection;
    @Mock private Statement          statement;

    @Test
    void create_withTenantInContext_setsSearchPathAndRoleFromLookedUpSchema() {
        // The factory must look up the persisted schema_name from
        // public.tenants and then SET search_path + SET ROLE accordingly.
        // Recomputing the schema name from the UUID is what caused the
        // original "writes land in public" bug.
        UUID tenantId = UUID.fromString("68b43674-68d5-48d3-9d89-1aae22da743c");
        String persistedSchema = "tenant_first_medfund";

        Statement lookupStmt = mock(Statement.class);
        Result    lookupResult = mock(Result.class);
        Row       row = mock(Row.class);
        when(row.get(0, String.class)).thenReturn(persistedSchema);
        when(lookupResult.map(any(BiFunction.class))).thenAnswer(inv -> {
            BiFunction<Row, RowMetadata, String> mapper = inv.getArgument(0);
            return Flux.just(mapper.apply(row, null));
        });
        when(lookupStmt.bind(anyString(), any())).thenReturn(lookupStmt);
        doReturn(Flux.just(lookupResult)).when(lookupStmt).execute();

        when(connection.createStatement(startsWith("SELECT schema_name"))).thenReturn(lookupStmt);
        when(connection.createStatement(startsWith("RESET ROLE"))).thenReturn(statement);
        when(connection.createStatement(startsWith("SET search_path"))).thenReturn(statement);
        when(connection.createStatement(startsWith("SET ROLE"))).thenReturn(statement);
        when(statement.execute()).thenReturn(Flux.empty());
        when(connection.close()).thenReturn(Mono.empty());
        doReturn(Mono.just(connection)).when(delegate).create();

        TenantAwareConnectionFactory factory = new TenantAwareConnectionFactory(delegate);

        Mono.from(factory.create())
                .contextWrite(ctx -> ctx.put("TENANT_ID", tenantId.toString()))
                .block();

        // RESET ROLE fires twice — once on the lookup connection (so the
        // lookup runs as the session user even if the pooled connection
        // was last in a tenant role) and once on the returned connection
        // before SET search_path / SET ROLE. Both invocations are
        // intentional; use atLeastOnce so the mock counts both.
        verify(connection, atLeastOnce()).createStatement(eq("RESET ROLE"));
        verify(connection).createStatement(eq("SET search_path TO " + persistedSchema + ", public"));
        verify(connection).createStatement(eq("SET ROLE " + persistedSchema + "_role"));
    }

    @Test
    void create_withoutTenant_resetsRoleAndPinsSearchPathToPublic() {
        // No tenant in context → drop any role leftover from a previous
        // pooled use and pin search_path to public. Platform / super-admin
        // paths run with the connection's session-user privileges.
        doReturn(Mono.just(connection)).when(delegate).create();
        when(connection.createStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(Flux.empty());

        TenantAwareConnectionFactory factory = new TenantAwareConnectionFactory(delegate);

        Mono.from(factory.create()).block();

        verify(connection).createStatement(eq("RESET ROLE"));
        verify(connection).createStatement(eq("SET search_path TO public"));
        verify(connection, never()).createStatement(contains("SET ROLE "));
        verify(connection, never()).createStatement(contains("SELECT schema_name"));
    }

    @Test
    void create_withUnknownTenant_fallsBackToPublic() {
        // If the lookup returns no row (unknown tenant id), we log a warning
        // and resolve to the public schema. This is safer than silently
        // succeeding against a phantom tenant_<uuid> schema.
        UUID tenantId = UUID.randomUUID();

        Statement lookupStmt = mock(Statement.class);
        Result    lookupResult = mock(Result.class);
        when(lookupResult.map(any(BiFunction.class))).thenReturn(Flux.empty()); // no rows
        when(lookupStmt.bind(anyString(), any())).thenReturn(lookupStmt);
        doReturn(Flux.just(lookupResult)).when(lookupStmt).execute();

        when(connection.createStatement(startsWith("SELECT schema_name"))).thenReturn(lookupStmt);
        when(connection.createStatement(startsWith("RESET ROLE"))).thenReturn(statement);
        when(connection.createStatement(startsWith("SET search_path"))).thenReturn(statement);
        when(connection.createStatement(startsWith("SET ROLE"))).thenReturn(statement);
        when(statement.execute()).thenReturn(Flux.empty());
        when(connection.close()).thenReturn(Mono.empty());
        doReturn(Mono.just(connection)).when(delegate).create();

        TenantAwareConnectionFactory factory = new TenantAwareConnectionFactory(delegate);

        Mono.from(factory.create())
                .contextWrite(ctx -> ctx.put("TENANT_ID", tenantId.toString()))
                .block();

        // Fallback "public" gets treated like a schema name, so
        // search_path lands at "public, public" — visually wonky but
        // semantically the right thing (caller has no tenant scope so
        // public-only is what they get). RESET ROLE fires twice — once
        // on the lookup connection and once on the returned connection.
        verify(connection, atLeastOnce()).createStatement(eq("RESET ROLE"));
        verify(connection).createStatement(eq("SET search_path TO public, public"));
    }
}
