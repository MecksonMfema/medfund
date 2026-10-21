package com.medfund.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level gate: the endpoint short-circuits with HTTP 403 unless the
 * tenant's {@code country_code} (from {@code public.tenants}, ISO 3166-1
 * alpha-2) exactly matches one of the given values. Case-sensitive; NULL
 * country on the tenant row denies.
 *
 * <p>Enforced by {@link CountryGuardAspect}. Used for cross-regulator reports
 * whose applicability is driven by the tenant's country of operation rather
 * than a specific regulator relationship — AML/STR, tax withheld, VAT
 * returns. Prudential returns (IPEC/CMS/NAIC) use {@link RequiresJurisdiction}
 * instead.
 *
 * <p>Example:
 * <pre>{@code
 * @PostMapping("/api/v1/reports/regulatory/vat/submit")
 * @RequiresCountry({"ZW", "ZA"})
 * @RequiresReport(ReportKey.VAT_RETURN)
 * @RequiresPermission({"finance:view", "finance:export_regulatory"})
 * public Mono<ReportJobSubmissionResponse> submit(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresCountry {

    /** One or more ISO 3166-1 alpha-2 country codes any of which grants access. */
    String[] value();
}
