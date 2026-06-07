package com.medfund.shared.testfixtures;

import com.medfund.shared.tenant.TenantContext;
import reactor.util.context.Context;

import java.util.function.Function;

/**
 * Test-side companion to {@link TenantContext}. The production code stores
 * the tenant ID in the Reactor {@code Context} (not a ThreadLocal), so a
 * {@code @BeforeEach} mutation can't transparently inject it. Instead this
 * helper:
 *
 * <ol>
 *   <li>{@link #set(String)} — invoked from {@link TenantContextExtension}
 *       via {@link WithTenant} to stash the current test's tenant ID</li>
 *   <li>{@link #put()} — returns a function tests pass to
 *       {@code .contextWrite(TenantTestContext.put())} on their reactive
 *       chain, applying the stashed tenant ID to the Reactor context</li>
 *   <li>{@link #current()} — read the active tenant ID directly for
 *       assertions and seed-data inserts</li>
 * </ol>
 *
 * <p>The ThreadLocal storage is acceptable here because test methods run
 * sequentially within a class — JUnit's default execution model. Parallel
 * test execution would require switching to a thread-bound context.
 */
public final class TenantTestContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantTestContext() {}

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String current() {
        String value = CURRENT.get();
        if (value == null) {
            throw new IllegalStateException(
                "No active tenant — annotate the test (or its class) with @WithTenant(\"<id>\")");
        }
        return value;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Convenience for reactive chains:
     * {@code mono.contextWrite(TenantTestContext.put())} applies the
     * current @WithTenant value via {@link TenantContext#put}.
     */
    public static Function<Context, Context> put() {
        String tenantId = current();
        return ctx -> TenantContext.put(ctx, tenantId);
    }

    /** Apply an explicit tenant ID — useful for cross-tenant leak tests. */
    public static Function<Context, Context> putExplicit(String tenantId) {
        return ctx -> TenantContext.put(ctx, tenantId);
    }
}
