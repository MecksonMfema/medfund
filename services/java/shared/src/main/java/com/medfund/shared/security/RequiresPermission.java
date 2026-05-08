package com.medfund.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level guard: the calling user must hold AT LEAST ONE of the listed
 * permission keys, or the call returns 403. Reads the permission set
 * populated by {@link PermissionResolverFilter} from the Reactor context.
 *
 * <p>For "all-of" semantics, stack two annotations on the same method (each
 * stack-frame fails closed independently) — explicit AND/OR support is left
 * out until a real use-case asks for it.
 *
 * <p>Example:
 * <pre>{@code
 * @PostMapping("/{id}/adjudicate")
 * @RequiresPermission(Permissions.CLAIMS_ADJUDICATE)
 * public Mono<Claim> adjudicate(@PathVariable UUID id) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** One or more permission keys; the user needs ANY of them. */
    String[] value();
}
