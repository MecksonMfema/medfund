package com.medfund.shared.testfixtures;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the tenant ID under which an integration test method runs. The
 * paired {@link TenantContextExtension} stores the value before each test
 * and clears it after, so test methods can call
 * {@link TenantTestContext#current()} from inside their reactive chains
 * without re-supplying the tenant manually:
 *
 * <pre>{@code
 * @Test
 * @WithTenant("11111111-1111-1111-1111-111111111111")
 * void schemeCreate_persistsAndAudits() {
 *     StepVerifier.create(
 *         schemeService.create(req, "actor-1")
 *             .contextWrite(TenantTestContext.put())
 *     ).expectNextCount(1).verifyComplete();
 * }
 * }</pre>
 *
 * <p>Why an annotation rather than a setUp helper: the tenant ID becomes part
 * of the test signature, surfacing in failure output ("WithTenant tenant-a")
 * and making cross-tenant tests obvious at a glance.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@ExtendWith(TenantContextExtension.class)
public @interface WithTenant {
    String value();
}
