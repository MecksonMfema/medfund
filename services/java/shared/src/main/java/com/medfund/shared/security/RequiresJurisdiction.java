package com.medfund.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level gate: the endpoint short-circuits with HTTP 403 unless the
 * tenant's {@code jurisdiction_code} (from {@code public.tenants}) exactly
 * matches one of the given values. Case-sensitive; NULL jurisdiction on the
 * tenant row denies.
 *
 * <p>Enforced by {@link JurisdictionGuardAspect}. Stack with
 * {@link com.medfund.shared.report.RequiresReport} and
 * {@code @RequiresPermission} — every gate must pass.
 *
 * <p>Cross-regulator reports (AML/STR, tax, VAT) do NOT use this annotation;
 * they gate on country code via {@link RequiresCountry} instead. Prudential
 * returns (IPEC, CMS, NAIC) that require a specific regulator context use
 * this one.
 *
 * <p>Example:
 * <pre>{@code
 * @PostMapping("/api/v1/reports/regulatory/ipec/quarterly-return")
 * @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
 * @RequiresReport(ReportKey.IPEC_QUARTERLY_RETURN)
 * @RequiresPermission({"finance:view", "finance:export_regulatory"})
 * public Mono<ReportJobSubmissionResponse> submit(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresJurisdiction {

    /** One or more {@code TenantJurisdiction} enum names any of which grants access. */
    String[] value();
}
