package com.medfund.finance.repository;

/**
 * SQL fragments for joining the platform provider registry.
 *
 * <p>Providers are platform-scoped: one row in {@code public.providers},
 * related to N tenants through {@code public.provider_tenants}. A finance
 * read therefore cannot simply join {@code providers} off the search path;
 * it has to qualify the platform table and gate it on a membership row for
 * the current tenant (CLAUDE.md Critical Rule 2, "platform tables with
 * tenant membership"). Without the gate a tenant would render the name of a
 * provider it has no contract with.
 *
 * <p>Every fragment binds {@code :tenantId}, so a caller that stitches one
 * in must also {@code .bind("tenantId", TenantContext.requireUuid(ctx))}
 * from the Reactor context.
 *
 * <p>The joins stay {@code LEFT}: the row on the finance side (a payment, a
 * note, a balance) is tenant-local and real regardless of whether the
 * provider is still contracted. A non-member provider renders with a blank
 * name, which is the same shape a payee-less row already renders as, rather
 * than making the financial row disappear from a ledger.
 */
final class ProviderJoins {

    private ProviderJoins() {}

    /**
     * {@code LEFT JOIN public.providers <alias> ON <alias>.id = <fkExpr>},
     * membership-guarded for the current tenant.
     *
     * @param alias  table alias the surrounding SELECT uses for providers
     * @param fkExpr the provider-id expression on the driving table
     */
    static String leftJoin(String alias, String fkExpr) {
        return " LEFT JOIN public.providers " + alias + " ON " + alias + ".id = " + fkExpr + " "
                + membership(alias);
    }

    /** The membership guard on its own, for hand-written joins and lookups. */
    static String membership(String alias) {
        return "   AND EXISTS (SELECT 1 FROM public.provider_tenants pt "
                + "             WHERE pt.provider_id = " + alias + ".id AND pt.tenant_id = :tenantId) ";
    }
}
