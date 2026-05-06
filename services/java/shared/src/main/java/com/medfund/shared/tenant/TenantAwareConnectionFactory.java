package com.medfund.shared.tenant;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * R2DBC connection factory decorator that sets PostgreSQL search_path
 * to the tenant's schema based on the Reactor context.
 */
public class TenantAwareConnectionFactory implements ConnectionFactory {

    private final ConnectionFactory delegate;

    public TenantAwareConnectionFactory(ConnectionFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return Mono.from(delegate.create())
                    .flatMap(conn -> {
                        if (tenantId != null) {
                            String schema = "tenant_" + tenantId.replace("-", "");
                            return Mono.from(conn.createStatement("SET search_path TO " + schema + ", public")
                                            .execute())
                                    .then(Mono.just(conn));
                        }
                        // Reset search_path for platform-level queries so a pooled
                        // connection that previously served a tenant request doesn't
                        // keep the tenant schema in its search_path.
                        return Mono.from(conn.createStatement("SET search_path TO public")
                                        .execute())
                                .then(Mono.just(conn));
                    });
        });
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return delegate.getMetadata();
    }
}
