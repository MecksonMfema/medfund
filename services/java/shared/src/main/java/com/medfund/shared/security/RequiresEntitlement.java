package com.medfund.shared.security;

import com.medfund.shared.entitlement.ModuleKey;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guard: the calling tenant must be entitled to the named {@link ModuleKey},
 * or the call returns 403. Resolves the tenant from the Reactor context
 * ({@code TenantContext}) and the entitlement from the shared
 * {@code TenantEntitlementRegistry}.
 *
 * <p>This is the defence-in-depth counterpart to the gateway's
 * {@code EntitlementGate}: the gateway is the primary edge gate (keyed off a
 * hand-maintained route-prefix map), and this aspect is the safety net that
 * catches a KYC route whose prefix-map entry was forgotten (G4). Keep both.
 *
 * <p>May be placed on a controller class (gates every handler) or on an
 * individual method. The aspect checks the method first, then the declaring
 * class.
 *
 * <p>Example:
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/v1/verifications")
 * @RequiresEntitlement(ModuleKey.KYC)
 * public class VerificationController { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresEntitlement {

    /** The module a tenant must be entitled to in order to reach the method. */
    ModuleKey value();
}
