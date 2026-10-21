package com.medfund.shared.report;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level gate: the endpoint short-circuits with HTTP 403 when the
 * tenant admin has disabled the given {@link ReportKey} via
 * {@code public.tenant_report_config}. An absent config row defaults to
 * enabled so newly-shipped reports are visible without a per-tenant
 * migration.
 *
 * <p>Enforced by {@link ReportGuardAspect} — same integration model as
 * {@code @RequiresPermission} in {@code com.medfund.shared.security}.
 *
 * <p>Stack this with {@code @RequiresPermission} to combine a tenant
 * feature-toggle gate with role-based access. Order does not matter
 * because both fail closed.
 *
 * <p>Example:
 * <pre>{@code
 * @GetMapping("/api/v1/reports/aged-debtors")
 * @RequiresReport(ReportKey.AGED_DEBTORS)
 * @RequiresPermission(Permissions.FINANCE_VIEW_DEBTORS)
 * public Mono<ReportResponse<AgedDebtorsPayload>> agedDebtors(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresReport {

    /** Report catalogue key this endpoint serves. */
    ReportKey value();
}
