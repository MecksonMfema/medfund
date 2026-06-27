package com.medfund.shared.tenant;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * R2DBC connection factory decorator that scopes every connection to the
 * caller's tenant.
 *
 * <p>Two complementary mechanisms are applied on every acquire:
 *
 * <ol>
 *   <li><b>{@code SET search_path}</b> targets the tenant's schema so
 *       unqualified table names ({@code SELECT … FROM members}) resolve
 *       to that schema first, then to {@code public} for shared platform
 *       tables.</li>
 *   <li><b>{@code SET ROLE}</b> drops connection privileges down to the
 *       tenant-scoped role provisioned by Flyway V117. The role has
 *       {@code USAGE} only on its own schema and a whitelisted set of
 *       public tables — so an attempt to read or write
 *       {@code tenant_other.anything} returns {@code permission denied}
 *       at the DB layer instead of silently succeeding.</li>
 * </ol>
 *
 * <p>For platform-context connections (no tenant in the Reactor context),
 * {@code RESET ROLE} undoes any role left over from a previous pooled
 * use and {@code search_path} resets to {@code public}.
 *
 * <p>The schema name persisted on {@code public.tenants.schema_name} is
 * derived from the tenant <em>slug</em> at provisioning time
 * (e.g. {@code tenant_first_medfund}). The tenant id carried in the
 * Reactor context is the tenant's UUID, so we look the schema name up
 * here rather than recomputing it from the UUID — a recomputed phantom
 * name was the root cause of the original "writes land in public" bug.
 *
 * <p>The role name is conventionally {@code <schema_name>_role}, kept
 * in sync with the V117 Flyway function
 * {@code public.provision_tenant_role(text)}.
 *
 * <p><b>Caching.</b> Tenant rows are created once and almost never
 * move; a process-lifetime in-memory cache is plenty for both dev and
 * prod. If a tenant's {@code schema_name} ever rotates the service is
 * expected to restart as part of that operation.
 */
public class TenantAwareConnectionFactory implements ConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareConnectionFactory.class);

    /**
     * Defence in depth: a schema name fetched from the DB is still
     * interpolated into a SQL string, so reject anything that doesn't
     * look like a plain SQL identifier before using it. The same regex
     * gates the role name ({@code schema + "_role"}).
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final ConnectionFactory delegate;
    private final ConcurrentHashMap<String, String> schemaCache = new ConcurrentHashMap<>();

    public TenantAwareConnectionFactory(ConnectionFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            // Anything that doesn't parse as a UUID is treated as a
            // platform-level request — covers (a) genuinely missing
            // tenant context, and (b) the {@code tenant_id = "platform"}
            // sentinel the bootstrap-keycloak script stamps on
            // super-admin JWTs. Without this branch the factory would
            // fall back to a phantom {@code public_role}, which doesn't
            // exist, and the connection acquire would error out.
            if (tenantId == null || !isUuid(tenantId)) {
                return openWithState("public", null);
            }
            return resolveSchemaName(tenantId)
                    .flatMap(schema -> openWithState(schema + ", public", schema + "_role"));
        });
    }

    private static boolean isUuid(String s) {
        try { UUID.fromString(s); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    /**
     * Bring a freshly-acquired connection to the requested state:
     * {@code RESET ROLE} first so any leftover role from a previous
     * pool slot is cleared; {@code SET search_path}; and, when a tenant
     * is in context, {@code SET ROLE} into the tenant role so the
     * connection inherits only that role's grants.
     */
    private Mono<Connection> openWithState(String searchPath, String role) {
        return Mono.from(delegate.create()).flatMap(conn -> {
            Mono<Void> resetRole = Mono.from(
                    conn.createStatement("RESET ROLE").execute()).then();
            Mono<Void> setSearchPath = Mono.from(
                    conn.createStatement("SET search_path TO " + searchPath).execute()).then();
            Mono<Void> setRole = role == null
                    ? Mono.empty()
                    : Mono.from(conn.createStatement("SET ROLE " + role).execute()).then();
            return resetRole.then(setSearchPath).then(setRole).then(Mono.just(conn));
        });
    }

    private Mono<String> resolveSchemaName(String tenantId) {
        String cached = schemaCache.get(tenantId);
        if (cached != null) return Mono.just(cached);
        return lookupSchemaName(tenantId)
                .doOnNext(name -> schemaCache.put(tenantId, name))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("No public.tenants row for tenant id {} — search_path will resolve " +
                                "to public only. Writes from this request will land in the platform schema.",
                                tenantId)).then(Mono.just("public")));
    }

    /**
     * Open a temporary connection through the raw delegate, force it
     * back to the session user with {@code RESET ROLE} (in case the
     * pooled connection was last used by a tenant), and query
     * {@code public.tenants}. The connection is closed via
     * {@code usingWhen} whether the query succeeds, errors, or is
     * cancelled.
     */
    private Mono<String> lookupSchemaName(String tenantId) {
        final UUID id;
        try {
            id = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return Mono.usingWhen(
                Mono.from(delegate.create()).flatMap(conn ->
                        Mono.from(conn.createStatement("RESET ROLE").execute())
                                .then(Mono.from(conn.createStatement(
                                        "SET search_path TO public").execute()))
                                .then(Mono.just(conn))),
                conn -> Mono.from(conn.createStatement(
                                "SELECT schema_name FROM public.tenants WHERE id = $1")
                        .bind("$1", id)
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, meta) ->
                                row.get(0, String.class))))
                        .filter(name -> name != null && SAFE_IDENTIFIER.matcher(name).matches()),
                Connection::close);
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return delegate.getMetadata();
    }
}
